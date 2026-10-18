#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T19 反向验证驱动：逐条注入 bug → 跑测试 → 必须变红 → 还原并刷新 mtime。

为什么用脚本而不是手工：每次注入都要「改文件 → 跑 → 还原 → os.utime 刷新 mtime」，
手工做会漏掉最后一步，而漏掉它会让 maven 认为源文件没变、不重新编译，
于是出现「Spring 上下文加载失败」这类假红（项目里踩过）。

用法： python mutate_t19.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(ROOT, "mutate-run.log")

MUTATIONS = [
    dict(
        id="M1",
        file="src/main/java/dev/learning/mall/config/SecurityConfig.java",
        old="            .anyRequest().authenticated())",
        new='            .requestMatchers("/api/wallet/**").permitAll()\n            .anyRequest().authenticated())',
        test="WalletControllerTest",
        must_fail=["anonymousCannotReadOrTopUpWallet"],
        why="把 /api/wallet/** 放行 → 未登录必须不再是 401（要么 200 要么 500，总之不是 401）",
    ),
    dict(
        id="M2",
        file="src/main/java/dev/learning/mall/wallet/web/WalletController.java",
        old="        return viewOf(principal.getName());",
        new='        return viewOf("someone-else");',
        test="WalletControllerTest",
        must_fail=["loggedInUserSeesBalanceAndOpeningEntry"],
        why="返回别人的钱包（无视 Principal）→ 余额结构断言必须红",
    ),
    dict(
        id="M3",
        file="src/main/java/dev/learning/mall/wallet/web/WalletController.java",
        old="        accounts.topUp(username);\n        return viewOf(username);",
        new="        return viewOf(username);",
        test="WalletControllerTest",
        must_fail=["topUpRaisesBalanceAndHeadsTheLedger"],
        why="充值端点不真的调 topUp → 「余额真的增加」必须红",
    ),
    dict(
        id="M4",
        file="src/main/java/dev/learning/mall/wallet/web/WalletController.java",
        old="    private static final int LEDGER_LIMIT = 20;",
        new="    private static final int LEDGER_LIMIT = 5;",
        test="WalletControllerTest",
        must_fail=["ledgerIsCapped"],
        why="条数上限 20 → 5 → 「一次最多 20 条」必须红",
    ),
    dict(
        id="M5",
        file="src/main/java/dev/learning/mall/wallet/internal/WalletService.java",
        old="        if (granted + amount > ceiling) {",
        new="        if (false) {",
        test="WalletControllerTest",
        must_fail=["topUpBeyondCeilingIsRejected"],
        why="去掉累计上限判定 → 「超上限 409」必须红",
    ),
    dict(
        id="M6",
        file="src/main/java/dev/learning/mall/wallet/internal/WalletService.java",
        old="        if (!properties.isTopupEnabled()) {",
        new="        if (false) {",
        test="WalletControllerTest",
        must_fail=["topUpDisabledIsRejected"],
        why="去掉总开关判定 → 「关闭时 409」必须红",
    ),
    dict(
        id="M7",
        file="src/main/java/dev/learning/mall/wallet/internal/WalletLedgerRepository.java",
        old="ORDER BY id DESC LIMIT :limit",
        new="ORDER BY id ASC LIMIT :limit",
        test="WalletControllerTest",
        must_fail=["topUpRaisesBalanceAndHeadsTheLedger", "ledgerIsCapped"],
        why="流水改成旧的在前 → 「最新一条是这次充值」与「截的是最新 20 条」必须红",
    ),
    dict(
        id="M8",
        file="src/main/java/dev/learning/mall/order/internal/OrderTransactions.java",
        old='throw BusinessException.conflict("PAYMENT_MISMATCH", "订单已使用另一种方式支付");',
        new='throw BusinessException.conflict("INVALID_STATE", "订单已使用另一种方式支付");',
        test="MallIntegrationTest",
        must_fail=["concurrentPaymentsHaveOneReceiptAndOnePaidEvent"],
        why="换支付方式支付的错误码改掉 → MallIntegrationTest 第 178 行的收紧断言必须红",
    ),
    dict(
        id="M9",
        file="src/main/java/dev/learning/mall/order/internal/OrderTransactions.java",
        old='if (!row.status().equals("PENDING")) throw BusinessException.conflict("INVALID_STATE", "已支付订单不能取消");',
        new='if (!row.status().equals("PENDING")) throw BusinessException.conflict("PAYMENT_MISMATCH", "已支付订单不能取消");',
        test="MallIntegrationTest",
        must_fail=["concurrentPaymentsHaveOneReceiptAndOnePaidEvent"],
        why="已支付订单取消的错误码改掉 → 第 184/185 行的 INVALID_STATE 断言必须红",
    ),
    dict(
        id="M10",
        file="src/test/java/dev/learning/mall/MallIntegrationTest.java",
        old='.isEqualTo("PAYMENT_MISMATCH");',
        new='.isEqualTo("NO_SUCH_CODE");',
        test="MallIntegrationTest#concurrentPaymentsHaveOneReceiptAndOnePaidEvent",
        must_fail=["concurrentPaymentsHaveOneReceiptAndOnePaidEvent"],
        why="把断言里的期望码改错（自证：这条断言真的被执行到，不是一条走不到的绿）",
    ),
]

SPURIOUS = ("ApplicationContext failure", "NoClassDefFoundError", "写入", "COMPILATION ERROR",
            "ClassNotFoundException")


def read(path, errors="strict"):
    # 源码一律严格 UTF-8（还原必须一字不差）；maven 日志在 Windows 控制台下可能是 GBK，容错读。
    with open(path, "r", encoding="utf-8", errors=errors) as fh:
        return fh.read()


def restore(path, text):
    """还原并刷新 mtime；还原后立刻回读校验，避免「以为还原了其实没有」。"""
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.utime(path, None)
    return read(path) == text


def inject(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    # 刷新 mtime：否则 maven 认为源文件没变、不重编译（会出现「上下文加载失败」这类假红）。
    os.utime(path, None)


def run_test(test):
    cmd = 'mvn -o test -Dtest="%s"' % test
    with open(LOG, "w", encoding="utf-8", errors="replace") as fh:
        subprocess.run(cmd, cwd=ROOT, shell=True, stdout=fh, stderr=subprocess.STDOUT, timeout=900)
    text = read(LOG, errors="replace")
    spurious = [s for s in SPURIOUS if s in text]
    build_ok = "BUILD SUCCESS" in text
    failed = sorted(set(re.findall(r"^\[ERROR\]\s+\S+\.(\w+)\s", text, re.M)))
    return build_ok, failed, spurious, text


def main():
    rows = []
    for m in MUTATIONS:
        path = os.path.join(ROOT, m["file"])
        original = read(path)
        if m["old"] not in original:
            rows.append((m["id"], "SKIP", "注入点没找到（文件已被改动）：%s" % m["old"][:60]))
            continue
        verdict = ""
        try:
            inject(path, original.replace(m["old"], m["new"], 1))
            attempt, build_ok, failed, spurious = 0, None, None, None
            while attempt < 3:
                attempt += 1
                build_ok, failed, spurious, _ = run_test(m["test"])
                if not spurious:
                    break
            hit = [t for t in m["must_fail"] if t in failed]
            # 判定：变红 = 构建失败 且 预期的那条用例真的红了；
            # 构建失败但失败的是上下文/编译（并发抖动）= 不可信的假红，如实报出来。
            if build_ok:
                verdict = "GREEN（假绿！断言没抓到这个 bug）"
            elif spurious:
                verdict = "RED?（不可信：%s）" % ",".join(spurious)
            elif hit:
                verdict = "RED ✅（命中 %s）" % ",".join(hit)
            else:
                verdict = "RED（但不是预期的用例：%s）" % (",".join(failed) or "无")
        except Exception as exc:  # 哪怕跑挂了也必须把文件还原回去
            verdict = "脚本异常：%s" % exc
        finally:
            rows.append((m["id"], verdict, m["why"]))
            if not restore(path, original):
                rows.append((m["id"], "还原失败！", "文件内容与原始不一致，必须手工核对"))
    print("\n================ 反向验证结果 ================")
    for mid, verdict, why in rows:
        print("%-4s %-46s %s" % (mid, verdict, why))
    print("=============================================")


if __name__ == "__main__":
    main()
