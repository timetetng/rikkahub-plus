#!/system/bin/sh
# 这是 /data/local/exec-tool.sh 的可移植副本（设备侧运维脚本）。
# 装到设备：cp 到 /data/local/exec-tool.sh && chmod 755，容器名靠第 2 参数或改下面的 CONTAINER=
# ============================================================================
# exec-tool.sh  v6   (2026-09-14)   统一命令执行器
# ----------------------------------------------------------------------------
# 用法(推荐, 明文stdin):  su -c '/data/local/exec-tool.sh <mode>' <<'XEOF'
#                          命令...（引号/$ 全安全, 原样执行）
#                        XEOF
# 用法(指定容器):         su -c '/data/local/exec-tool.sh arch <容器名>'
#                         （第 2 参数仅 arch 模式用，缺省 arch；旧 base64 模式已移除）
#
# mode:
#   arch    = Droidspaces 容器 "arch"（systemd，推荐日常用）  <- 默认
#   termux  = ZeroTermux 环境
#   root    = 全局 mount namespace 真 root
#   chroot  = 旧 Arch chroot(/data/local/arch)  已废弃，仅应急
#
# -- v4 变更 (2026-09-10) ---------------------------------------------------
#   root 方案已从 Magisk 换成 KernelSU-Next；容器从自建 chroot 换成
#   Droidspaces（systemd 作 PID1，见 /data/local/Droidspaces/）。
#   - arch 模式改为 droidspaces --name=arch run sh -c "$CMD"：
#     本脚本不再做任何 mount，彻底消除挂载传播风险。
#   - 旧 chroot 实现保留为 chroot 模式（应急），仍带 shared 门禁。
#   - su 路径/域已变：KernelSU 的 su 在 /system/bin/su，域 u:r:ksu:s0
#     （不再是 /product/bin/su 与 u:r:magisk:s0）。
#
# -- 历史 (v3, 仍适用) -----------------------------------------------------
#   v2 是 Rikkahub/系统卡死黑屏的元凶：在 PID1 的 mount ns（/=shared:1,
#   /data=shared:70）里 mount，事件传播到全系统 850+ 个 mount ns。
#   v3 起改为私有 ns 内挂载。铁律：绝不在 PID1 的 mount ns 里 mount/umount。
# ============================================================================

MODE="$1"
CONTAINER="${2:-arch}"        # 可选第 2 参数：容器名（仅 arch 模式用），默认 arch

DS=/data/local/Droidspaces/bin/droidspaces

CMD=$(cat)
[ -z "$CMD" ] && { echo "ERR: empty command"; exit 2; }

CHROOT_INNER='
CMD="$1"
R=/data/local/arch
if awk "\$5==\"/\" && /shared:/ {f=1} END{exit !f}" /proc/self/mountinfo; then
  echo "ERR: 当前 mount namespace 是 SHARED, 拒绝挂载(会向全系统传播->卡死)。" >&2
  exit 3
fi
mnt() { grep -q " $1 " /proc/self/mountinfo; }
mnt "$R"          || mount --bind "$R" "$R" || { echo "ERR: bind rootfs failed" >&2; exit 4; }
mkdir -p "$R/dev/pts" "$R/var/cache/pacman/pkg"
mnt "$R/dev"      || mount --bind /dev "$R/dev"
mnt "$R/dev/pts"  || mount --bind /dev/pts "$R/dev/pts"
mnt "$R/proc"     || mount -t proc proc "$R/proc"
mnt "$R/sys"      || mount -t sysfs sysfs "$R/sys"
mnt "$R/var/cache/pacman/pkg" || mount --bind "$R/var/cache/pacman/pkg" "$R/var/cache/pacman/pkg"
exec chroot "$R" /usr/bin/env \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/bin/site_perl:/usr/bin/vendor_perl:/usr/bin/core_perl \
  HOME=/root TERM=xterm-256color \
  /bin/bash -c "$CMD"
'

TERMUX_INNER='
exec /data/user/0/com.termux/files/usr/bin/env \
  -i HOME=/data/user/0/com.termux/files/home \
     PREFIX=/data/user/0/com.termux/files/usr \
     TMPDIR=/data/user/0/com.termux/files/usr/tmp \
     LD_LIBRARY_PATH=/data/user/0/com.termux/files/usr/lib \
     PATH=/data/user/0/com.termux/files/usr/bin:/system/bin:/vendor/bin \
  /data/user/0/com.termux/files/usr/bin/bash -c "$1"
'

case "$MODE" in
  arch)
    [ -x "$DS" ] || { echo "ERR: 找不到 $DS（Droidspaces 未安装？）" >&2; exit 4; }
    exec "$DS" --name="$CONTAINER" run sh -c "set -a; [ -f /etc/agent.env ] && . /etc/agent.env; set +a
$CMD"
    ;;
  root)
    exec nsenter -t 1 -m -- /system/bin/sh -c "set -a; [ -f /data/local/agent.env ] && . /data/local/agent.env; set +a
$CMD"
    ;;
  termux)
    exec nsenter -t 1 -m -- /system/bin/sh -c "$TERMUX_INNER" x "$CMD"
    ;;
  chroot)
    echo "WARN: chroot 模式已废弃（旧 /data/local/arch），仅应急使用。" >&2
    if [ -x /system/bin/unshare ]; then
      exec /system/bin/unshare -m -- /system/bin/sh -c "$CHROOT_INNER" x "$CMD"
    else
      exec /system/bin/sh -c "$CHROOT_INNER" x "$CMD"
    fi
    ;;
  *)
    echo "ERR: unknown mode [$MODE]. use arch|termux|root|chroot" >&2
    exit 2
    ;;
esac
