#!/bin/bash
t=$1; docker rm -f os-$t >/dev/null 2>&1
COMMON="--name os-$t --hostname os-$t --privileged --tmpfs /run --tmpfs /run/lock -v /tmp/ostest/dl:/opt/dl:ro -v /root/.ccr/ca-bundle.crt:/opt/ca.crt:ro -v /home/user/Hy2Setup/app/build/scripts:/opt/scripts:ro"
if [ "$t" = u26.04 ]; then
  docker run -d $COMMON --cgroupns=private --entrypoint bash hy2test:$t -c 'umount /sys/fs/cgroup 2>/dev/null; mount -t cgroup2 none /sys/fs/cgroup && exec /sbin/init' >/dev/null
else
  docker run -d $COMMON --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw hy2test:$t >/dev/null
fi
