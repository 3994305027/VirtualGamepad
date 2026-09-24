#!/bin/sh
# 用法：
#   sh 改版本号.sh            -> 用当前时间（北京时间）
#   sh 改版本号.sh 26.9.25.10.0   -> 用指定时间
#
# 自动算出 versionCode 和 versionName，写进 app/build.gradle

set -e
cd "$(dirname "$0")"

if [ -n "$1" ]; then
    VN="$1"
else
    # 北京时间 = UTC+8
    # %-m 是 GNU date 的"去掉前导零"，某些精简版 date 不支持，
    # 不支持时退回去零的版本再用 sed 把前导零抹掉
    VN=$(TZ='CST-8' date '+%y.%-m.%-d.%-H.%-M' 2>/dev/null) || VN=""
    if [ -z "$VN" ] || echo "$VN" | grep -q '%-'; then
        VN=$(TZ='CST-8' date '+%y.%m.%d.%H.%M' | sed 's/\.0\([0-9]\)/.\1/g')
    fi
fi

# 校验：必须是 5 段，形如 26.9.24.13.17
echo "$VN" | grep -Eq '^[0-9]{2}\.[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,2}$' || {
    echo "格式不对：'$VN'"
    echo "要的是  年.月.日.时.分  例如 26.9.24.13.17"
    exit 1
}

YY=$(echo "$VN" | cut -d. -f1)
MO=$(echo "$VN" | cut -d. -f2)
DD=$(echo "$VN" | cut -d. -f3)
HH=$(echo "$VN" | cut -d. -f4)
MM=$(echo "$VN" | cut -d. -f5)

# 距 2026-01-01 00:00（北京时间）的分钟数
VC=$(TZ='CST-8' awk -v y="20$YY" -v mo="$MO" -v d="$DD" -v h="$HH" -v mi="$MM" 'BEGIN{
    # 用 UTC 基准算，避免本地时区干扰
    base = 1767196800;                       # 2026-01-01 00:00 +08:00
    days = int((14 - mo) / 12);
    y2 = y + 4800 - days;
    m2 = mo + 12 * days - 3;
    jd = d + int((153 * m2 + 2) / 5) + 365 * y2 + int(y2/4) - int(y2/100) + int(y2/400) - 32045;
    t = (jd - 2440588) * 86400 + h * 3600 + mi * 60 - 8 * 3600;
    printf "%d\n", int((t - base) / 60);
}')

# int 上限 2147483647
if [ "$VC" -gt 2147483647 ] || [ "$VC" -lt 1 ]; then
    echo "算出来的 versionCode 不合法：$VC"
    exit 1
fi

python3 - "$VC" "$VN" <<'PY'
import sys, re
vc, vn = sys.argv[1], sys.argv[2]
p = 'app/build.gradle'
s = open(p, encoding='utf-8').read()
s2 = re.sub(r'versionCode \d+',  'versionCode ' + vc, s, count=1)
s2 = re.sub(r'versionName "[^"]*"', 'versionName "%s"' % vn, s2, count=1)
if s2 == s:
    print("没找到 versionCode / versionName，文件没改")
    sys.exit(1)
open(p, 'w', encoding='utf-8').write(s2)
print("已写入  versionCode %s" % vc)
print("        versionName \"%s\"" % vn)
PY
