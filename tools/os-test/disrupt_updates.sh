#!/bin/bash
# обрывы связи во время обновления: убить SSH-сессии и на 40 с выключить sshd
sleep 45
docker exec os-d12 bash -c 'pkill -f "sshd: root@" ; echo "[disrupt] sessions killed at $(date +%T)"'
sleep 10
docker exec os-d12 bash -c 'systemctl stop ssh.socket ssh 2>/dev/null; pkill -f "sshd: root@"; echo "[disrupt] sshd stopped $(date +%T)"'
sleep 40
docker exec os-d12 bash -c 'systemctl start ssh; echo "[disrupt] sshd started $(date +%T)"'
