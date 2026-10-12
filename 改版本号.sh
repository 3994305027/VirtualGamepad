#!/bin/sh
# 按【当前真实时间】重算 versionCode / versionName，并同步更新日志首条。
# 用法：sh 改版本号.sh
# 说明：versionCode = 距 2026-01-01 00:00 的分钟数
#       versionName = 年.月.日.时.分（年取两位，如 26.10.12.18.49）
set -e
cd "$(dirname "$0")"
VC=$(python3 -c "
from datetime import datetime
print(int((datetime.now()-datetime(2026,1,1)).total_seconds()//60))")
VN=$(TZ='CST-8' date '+%y.%m.%d.%H.%M')
python3 - "$VC" "$VN" <<'PY'
import re,sys
vc,vn=sys.argv[1],sys.argv[2]
p='app/build.gradle'; s=open(p).read()
s=re.sub(r'versionCode \d+','versionCode '+vc,s)
s=re.sub(r'versionName "[^"]*"','versionName "%s"'%vn,s)
open(p,'w').write(s)
p='app/src/main/assets/更新日志.txt'; s=open(p,encoding='utf-8').read()
s=re.sub(r'^@@VER \d+ [\d.]+', '@@VER %s %s'%(vc,vn), s, count=1, flags=re.M)
open(p,'w',encoding='utf-8').write(s)
print('versionCode =',vc)
print('versionName =',vn)
PY
