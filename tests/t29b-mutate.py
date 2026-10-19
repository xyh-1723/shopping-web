"""T29b 四条修复的反向验证脚本：注回 bug → 测试必须变红 → 还原 → 全绿。

为什么单独写一个脚本，而不是手工改了再改回来：
    手工还原最容易出的事故是「以为还原了，其实没有」——git checkout 会顺手把
    没提交的哨兵改动一起还原，而凭肉眼比对一段中文注释里的三元表达式，谁都看不准。
    这个脚本把「备份 → 精确替换 → 还原 → 刷新 mtime」做成机械动作，任何一步
    对不上就直接报错退出，不会静默跑成全绿。

换行符：这些源文件在 Windows 工作树里是 CRLF，脚本里写的是 LF。
内部统一先归一化成 LF 再做匹配，写回时再还原成文件原本的换行符。

用法（由外部 loop 驱动，脚本只负责改文件和吐出要跑的命令）：
    python tests/t29b-mutate.py list
    python tests/t29b-mutate.py apply  <id>   # 注入变异，stdout 首行是待执行的测试命令
    python tests/t29b-mutate.py revert <id>   # 还原原文件并刷新 mtime
"""
import io
import os
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BACKUP_DIR = os.path.join(ROOT, "target", "t29b-mutate-backups")

CATALOG_TOOLS = "src/main/java/dev/learning/mall/assistant/internal/CatalogTools.java"
STAFF_TOOLS = "src/main/java/dev/learning/mall/assistant/internal/StaffTools.java"
APP_JS = "src/main/resources/static/js/app.js"
APPLICATION_YML = "src/main/resources/application.yml"

MVN = "mvn -o test -Dtest=%s -DfailIfNoTests=false"
NODE_RENDER = "node --test tests/catalog-render-limit.test.cjs"

# searchProducts 里被替换掉的那一处三元表达式（原样抄自修复后的代码）。
PICKED_BLOCK = "\n".join([
    "        List<ProductView> picked = browsing",
    "                ? spreadAcrossCategories(filtered)",
    "                : filtered.stream().limit(MAX_RESULTS).toList();",
])

MUTATIONS = [
    {
        "id": "B2-M1",
        "desc": "searchProducts 去掉条数上限（回到「506 件全塞进上下文」的 bug）",
        "file": CATALOG_TOOLS,
        "find": PICKED_BLOCK,
        "replace": "        List<ProductView> picked = filtered;",
        "cmd": MVN % "CatalogToolsScaleLimitTest",
    },
    {
        "id": "B2-M2",
        "desc": "searchProducts 保留上限但改回纯价格升序（「你们有什么」= 最便宜的 12 件）",
        "file": CATALOG_TOOLS,
        "find": PICKED_BLOCK,
        "replace": "        List<ProductView> picked = filtered.stream().limit(MAX_RESULTS).toList();",
        "cmd": MVN % "CatalogToolsScaleLimitTest",
    },
    {
        "id": "B3-M1",
        "desc": "inventoryAlerts 去掉明细上限（低库存几百件时工具失效）",
        "file": STAFF_TOOLS,
        "find": "        for (ProductView p : low.stream().limit(MAX_ALERTS).toList()) {",
        "replace": "        for (ProductView p : low) {",
        "cmd": MVN % "StaffToolsInventoryAlertLimitTest",
    },
    {
        "id": "B3-M2",
        "desc": "inventoryAlerts 不再报 total（店员误以为补完 20 件就没事了）",
        "file": STAFF_TOOLS,
        "find": '        result.put("total", low.size());',
        "replace": "        // total 被移除（变异）",
        "cmd": MVN % "StaffToolsInventoryAlertLimitTest",
    },
    {
        "id": "B1-M1",
        "desc": "关掉 server.compression（112KB 响应体裸奔）",
        "file": APPLICATION_YML,
        "find": "\n".join(["  compression:", "    enabled: true"]),
        "replace": "\n".join(["  compression:", "    enabled: false"]),
        "cmd": MVN % "HttpCompressionTest",
    },
    {
        "id": "B4-M1",
        "desc": "renderProducts 去掉渲染上限（506 件一次 innerHTML 324KB）",
        "file": APP_JS,
        "find": "  const shown = visible.slice(0, RENDER_LIMIT);",
        "replace": "  const shown = visible;",
        "cmd": NODE_RENDER,
    },
]


def by_id(mutation_id):
    for mutation in MUTATIONS:
        if mutation["id"] == mutation_id:
            return mutation
    return None


def read_text(rel_path):
    # newline="" ：关掉通用换行转换，保留文件里真实的 \r\n，匹配与写回才对得上。
    with io.open(os.path.join(ROOT, rel_path), "r", encoding="utf-8", newline="") as handle:
        return handle.read()


def write_text(rel_path, text):
    with io.open(os.path.join(ROOT, rel_path), "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def to_lf(text):
    return text.replace("\r\n", "\n")


def eol_of(text):
    return "\r\n" if "\r\n" in text else "\n"


def from_lf(text, eol):
    return text.replace("\n", eol) if eol == "\r\n" else text


def apply_mutation(mutation):
    target = os.path.join(ROOT, mutation["file"])
    original = read_text(mutation["file"])
    lf = to_lf(original)
    needle = to_lf(mutation["find"])
    if needle not in lf:
        sys.stderr.write("[%s] 变异点没找到，脚本已过期：%s\n" % (mutation["id"], mutation["file"]))
        return 2
    mutated = from_lf(lf.replace(needle, to_lf(mutation["replace"])), eol_of(original))
    os.makedirs(BACKUP_DIR, exist_ok=True)
    shutil.copyfile(target, os.path.join(BACKUP_DIR, os.path.basename(target)))
    write_text(mutation["file"], mutated)
    print(mutation["cmd"])
    return 0


def revert_mutation(mutation):
    target = os.path.join(ROOT, mutation["file"])
    backup = os.path.join(BACKUP_DIR, os.path.basename(target))
    if not os.path.exists(backup):
        sys.stderr.write("[%s] 找不到备份 %s，拒绝继续\n" % (mutation["id"], backup))
        return 2
    shutil.copyfile(backup, target)
    os.remove(backup)
    # 刷新 mtime：maven 按 mtime 决定要不要重编译，不刷新就会一直跑变异后的 class，
    # 出现「明明还原了还是红」的假象。
    os.utime(target, None)
    return 0


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "list":
        for mutation in MUTATIONS:
            print("%s\t%s" % (mutation["id"], mutation["desc"]))
        return 0

    action = sys.argv[1]
    mutation_id = sys.argv[2] if len(sys.argv) > 2 else ""
    mutation = by_id(mutation_id)
    if mutation is None:
        sys.stderr.write("没有找到变异 %s\n" % mutation_id)
        return 2
    if action == "apply":
        return apply_mutation(mutation)
    if action == "revert":
        return revert_mutation(mutation)
    sys.stderr.write("未知动作 %s（只支持 list/apply/revert）\n" % action)
    return 2


if __name__ == "__main__":
    sys.exit(main())
