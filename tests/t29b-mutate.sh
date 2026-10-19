#!/usr/bin/env bash
# T29b 反向验证：逐条注回 bug，跑对应的守护测试，断言必须真的变红；每条跑完立刻还原。
#
# 为什么要脚本化：手工「改回来」最大的风险是以为改回来了其实没有，
# 而 git checkout 会把没提交的哨兵改动一起还原。这里改文件与还原都由
# tests/t29b-mutate.py 完成，且每一步失败即中断，不会带着变异继续跑。
#
# 用法：bash tests/t29b-mutate.sh [变异 id...]（不给参数则跑全部）
set -uo pipefail
cd "$(dirname "$0")/.."

PYTHON="python"
[ -n "${PYTHON_BIN:-}" ] && PYTHON="$PYTHON_BIN"

mkdir -p target
ids=("$@")
if [ ${#ids[@]} -eq 0 ]; then
  mapfile -t ids < <("$PYTHON" tests/t29b-mutate.py list | cut -f1)
fi

declare -a summaries=()
overall=0

for id in "${ids[@]}"; do
  desc="$("$PYTHON" tests/t29b-mutate.py list | awk -F'\t' -v k="$id" '$1==k{print $2}')"
  log="target/t29b-mutate-${id}.log"

  cmd="$("$PYTHON" tests/t29b-mutate.py apply "$id")" || { echo "[${id}] 注入失败，中断"; overall=1; break; }

  bash -c "$cmd" >"$log" 2>&1
  rc=$?

  "$PYTHON" tests/t29b-mutate.py revert "$id" || { echo "[${id}] 还原失败，中断"; overall=1; break; }

  if [ $rc -eq 0 ]; then
    echo "[${id}] GREEN  ❌ 假绿——注回了 bug 测试却没红：${desc}"
    summaries+=("[${id}] 假绿")
    overall=1
    continue
  fi

  # 红了还不够：必须确认是断言红，而不是编译错误或 mvn 起不来（那等于没验）。
  if grep -qE "Tests run: [0-9]+, Failures: [1-9]" "$log"; then
    echo "[${id}] RED    ✅ 断言失败（$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$log" | tail -1)）：${desc}"
    summaries+=("[${id}] 真红")
  elif grep -qE "^# fail [1-9]" "$log"; then
    echo "[${id}] RED    ✅ 断言失败（node: $(grep -E "^# fail [0-9]+" "$log" | tr '\n' ' ')）：${desc}"
    summaries+=("[${id}] 真红")
  else
    echo "[${id}] RED    ❌ 无效——没跑到测试就失败（编译错误？），详见 ${log}"
    summaries+=("[${id}] 无效红")
    overall=1
  fi
done

echo
echo "================ 反向验证汇总 ================"
for line in "${summaries[@]}"; do echo "  ${line}"; done
echo "=============================================="
exit $overall
