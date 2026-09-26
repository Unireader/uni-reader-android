#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图标的**唯一真源**：`app/src/main/res/drawable/ic_*.xml` 全是本文件的生成物。

    python3 tools/icons/gen.py            # 生成 + 校验
    python3 tools/icons/gen.py --check    # 只校验，不落盘（CI/改完自检）
    python3 tools/icons/gen.py --sheet    # 顺带出一张对照大图（需要 matplotlib）

**为什么要生成而不是手写 28 个 XML**：手写那版飘成了三种线宽（1.6/1.8/2.0）、
四种视觉尺寸（`ic_nib` 内容只有 6..18，`ic_lock` 撑到 3..22 还差点被裁），
`ic_ruler` 靠 `<group rotation>` 把矩形甩出画布，`ic_eye` 的瞳孔是 `a3.2,3.2 0 1,0 0.01,0`
这种退化弧线技巧。根因是「同一套规格靠人肉复制 28 遍」。

现在规格只写一次（下面的常量 + KEYLINE），几何用点算出来，落盘前**逐个量 bbox**：
超出安全区、或者视觉重心偏离画布中心，直接报错不生成。

—— 规格 ——
* 画布 24×24，内容活动区 20×20（[2,22]），描边一律 1.8、round cap/join、无填充面。
* 定位线（照抄 Material 的 24dp keyline，实测这套比例在 22dp 显示尺寸下最稳）：
  方形 18、圆 ⌀19、横矩形 20×15、竖矩形 15×20。
* 每个图标的**描边外沿** bbox 必须落在 [0.6, 23.4] 内（不许被裁），
  中心必须在 12±0.4（不许一个偏左一个偏右）。
* 颜色一律 `#FFFFFFFF`，真实颜色由代码 `imageTintList` 决定（语义色，跟随深浅色）。
"""

import io
import math
import os
import sys

VIEW = 24.0
CENTER = 12.0
STROKE = 1.8
LIVE = (2.0, 22.0)        # 内容（不含描边）必须落在这里面
SAFE = (0.6, 23.4)        # 内容 + 描边外沿必须落在这里面（不许被画布裁掉）
CENTER_TOL = 0.4          # 视觉重心允许的偏移
SPAN_MIN, SPAN_MAX = 14.0, 20.4   # 长边尺寸区间：太小显得虚，太大显得胀

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")


# ───────────────────────── 几何：既出 pathData 又出可量的点 ─────────────────────────

def rot(deg, cx=CENTER, cy=CENTER):
    """绕 (cx,cy) 顺时针转 deg 度（屏幕坐标 y 向下）。
    只用在**圆弧半径相等**的路径上——刚体旋转不改变圆弧的 r/laf/sf，只挪端点。"""
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)

    def f(x, y):
        dx, dy = x - cx, y - cy
        return (cx + dx * ca - dy * sa, cy + dx * sa + dy * ca)
    return f


def _n(v):
    s = "%.3f" % v
    s = s.rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


class Path(object):
    """只支持绝对 M/L/C/A/Z，且圆弧限定 rx==ry。
    限死语法是故意的：这样 flatten() 能把每条路径**精确量出来**，校验才有意义。"""

    def __init__(self, tf=None):
        self.cmds = []
        self.tf = tf or (lambda x, y: (x, y))

    def _p(self, x, y):
        return self.tf(x, y)

    def M(self, x, y):
        self.cmds.append(("M", self._p(x, y)))
        return self

    def L(self, x, y):
        self.cmds.append(("L", self._p(x, y)))
        return self

    def C(self, x1, y1, x2, y2, x, y):
        self.cmds.append(("C", self._p(x1, y1) + self._p(x2, y2) + self._p(x, y)))
        return self

    def A(self, r, laf, sf, x, y):
        self.cmds.append(("A", (r, laf, sf) + self._p(x, y)))
        return self

    def Z(self):
        self.cmds.append(("Z", ()))
        return self

    def data(self):
        out = []
        for op, a in self.cmds:
            if op == "Z":
                out.append("Z")
            elif op == "A":
                out.append("A%s,%s 0 %d %d %s,%s" % (_n(a[0]), _n(a[0]), a[1], a[2], _n(a[3]), _n(a[4])))
            else:
                out.append(op + " ".join("%s,%s" % (_n(a[i]), _n(a[i + 1])) for i in range(0, len(a), 2)))
        return " ".join(out)

    def points(self, n=48):
        """展平成折线点集——bbox 就是量它。曲线/圆弧采样 48 段，误差 < 0.01。"""
        pts = []
        cur = None
        start = None
        for op, a in self.cmds:
            if op == "M":
                cur = (a[0], a[1])
                start = cur
                pts.append(cur)
            elif op == "L":
                cur = (a[0], a[1])
                pts.append(cur)
            elif op == "C":
                p0 = cur
                for i in range(1, n + 1):
                    t = float(i) / n
                    u = 1 - t
                    x = (u ** 3 * p0[0] + 3 * u * u * t * a[0] + 3 * u * t * t * a[2] + t ** 3 * a[4])
                    y = (u ** 3 * p0[1] + 3 * u * u * t * a[1] + 3 * u * t * t * a[3] + t ** 3 * a[5])
                    pts.append((x, y))
                cur = (a[4], a[5])
            elif op == "A":
                pts.extend(_arc(cur, a[0], a[1], a[2], (a[3], a[4]), n))
                cur = (a[3], a[4])
            elif op == "Z":
                if start:
                    pts.append(start)
                cur = start
        return pts

    def subpaths(self, n=48):
        """按 M 切段（画对照图时每段单独画，否则跳笔会连出直线）"""
        segs, cur_seg = [], []
        cur = start = None
        for op, a in self.cmds:
            if op == "M":
                if cur_seg:
                    segs.append(cur_seg)
                cur = (a[0], a[1])
                start = cur
                cur_seg = [cur]
            elif op == "L":
                cur = (a[0], a[1])
                cur_seg.append(cur)
            elif op == "C":
                p0 = cur
                for i in range(1, n + 1):
                    t = float(i) / n
                    u = 1 - t
                    cur_seg.append((u ** 3 * p0[0] + 3 * u * u * t * a[0] + 3 * u * t * t * a[2] + t ** 3 * a[4],
                                    u ** 3 * p0[1] + 3 * u * u * t * a[1] + 3 * u * t * t * a[3] + t ** 3 * a[5]))
                cur = (a[4], a[5])
            elif op == "A":
                cur_seg.extend(_arc(cur, a[0], a[1], a[2], (a[3], a[4]), n))
                cur = (a[3], a[4])
            elif op == "Z":
                if start:
                    cur_seg.append(start)
                cur = start
        if cur_seg:
            segs.append(cur_seg)
        return segs


def _arc(p0, r, laf, sf, p1, n):
    """SVG 端点式圆弧 → 采样点（rx=ry=r，x-rotation=0）"""
    x0, y0 = p0
    x1, y1 = p1
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    rx = ry = float(r)
    lam = dx2 * dx2 / (rx * rx) + dy2 * dy2 / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    sign = 1.0 if laf != sf else -1.0
    num = rx * rx * ry * ry - rx * rx * dy2 * dy2 - ry * ry * dx2 * dx2
    den = rx * rx * dy2 * dy2 + ry * ry * dx2 * dx2
    co = sign * math.sqrt(max(0.0, num / den)) if den else 0.0
    cxp, cyp = co * rx * dy2 / ry, -co * ry * dx2 / rx
    cx, cy = cxp + (x0 + x1) / 2.0, cyp + (y0 + y1) / 2.0
    th0 = math.atan2((dy2 - cyp) / ry, (dx2 - cxp) / rx)
    th1 = math.atan2((-dy2 - cyp) / ry, (-dx2 - cxp) / rx)
    dth = th1 - th0
    if sf == 0 and dth > 0:
        dth -= 2 * math.pi
    elif sf == 1 and dth < 0:
        dth += 2 * math.pi
    return [(cx + rx * math.cos(th0 + dth * i / float(n)),
             cy + ry * math.sin(th0 + dth * i / float(n))) for i in range(1, n + 1)]


# —— 常用形状 ——

def rrect(x0, y0, x1, y1, r, tf=None):
    p = Path(tf)
    p.M(x0 + r, y0).L(x1 - r, y0).A(r, 0, 1, x1, y0 + r)
    p.L(x1, y1 - r).A(r, 0, 1, x1 - r, y1)
    p.L(x0 + r, y1).A(r, 0, 1, x0, y1 - r)
    p.L(x0, y0 + r).A(r, 0, 1, x0 + r, y0).Z()
    return p


def circle(cx, cy, r, tf=None):
    return Path(tf).M(cx - r, cy).A(r, 1, 1, cx + r, cy).A(r, 1, 1, cx - r, cy).Z()


def line(x0, y0, x1, y1, tf=None):
    return Path(tf).M(x0, y0).L(x1, y1)


def poly(pts, close=False, tf=None):
    p = Path(tf)
    p.M(pts[0][0], pts[0][1])
    for x, y in pts[1:]:
        p.L(x, y)
    if close:
        p.Z()
    return p


class S(object):
    """一条描边路径"""
    kind = "stroke"

    def __init__(self, path):
        self.path = path


class F(object):
    """一块填充（只给圆点这种实心元素用；面积色块是拟物，不许）"""
    kind = "fill"

    def __init__(self, path):
        self.path = path


class Icon(object):
    def __init__(self, note, shapes, span=None):
        self.note = note          # 写进 XML 头，说明这个图标画的是什么
        self.shapes = shapes
        self.span = span or (SPAN_MIN, SPAN_MAX)


def chevron(dx, dy):
    """四个方向的角标共用同一份几何——从前上下/左右两套尺寸（12 宽 vs 14 高），
    并排放在一起一眼能看出不一样高。"""
    arm = 8.0     # 真机实测：7.2 时角标只有两笔、比邻键轻一档，8.0 才压得住
    ax, ay = arm * dx, arm * dy
    tip = (CENTER + ax * 0.5, CENTER + ay * 0.5)
    # 垂直于指向的两只手臂
    px, py = -dy, dx
    a = (tip[0] - ax + px * arm, tip[1] - ay + py * arm)
    b = (tip[0] - ax - px * arm, tip[1] - ay - py * arm)
    return poly([a, tip, b])


def circ_arrow(r, gap0, gap1, head=3.0):
    """缺口圆 + 箭头：`sync`。箭头长度按半径收着给，越界会被下面的校验挡住。"""
    def pt(deg):
        a = math.radians(deg)
        return (CENTER + r * math.cos(a), CENTER + r * math.sin(a))
    p = Path().M(*pt(gap1))
    p.A(r, 1, 1, *pt(gap0 + 360))
    end = pt(gap0 + 360)
    a = math.radians(gap0 + 360)
    t = (-math.sin(a), math.cos(a))          # 终点处的行进方向
    barbs = []
    for A in (145.0, -145.0):
        ca, sa = math.cos(math.radians(A)), math.sin(math.radians(A))
        barbs.append((end[0] + head * (t[0] * ca - t[1] * sa),
                      end[1] + head * (t[0] * sa + t[1] * ca)))
    return [S(p), S(poly([barbs[0], end, barbs[1]]))]


def rot_at(deg, cx, cy):
    """绕任意点转——`scratch` 里那支小笔要绕自己的中心转，不是绕画布中心"""
    return rot(deg, cx, cy)


def rot_move(deg, cx, cy, dx, dy):
    """绕 (cx,cy) 转 deg 度再整体平移 (dx,dy)。
    一头方一头尖的形状（铅笔）转完之后 bbox 重心不在轴心上，靠这个平移配平。"""
    r = rot(deg, cx, cy)

    def f(x, y):
        px, py = r(x, y)
        return (px + dx, py + dy)
    return f


def pen_shape(cx, cy, length, half, nib, deg=45.0):
    """**一支笔只画一次**：`pen`（切换笔）与 `scratch`（草稿纸）用的是同一支，
    只是尺寸和落点不同。两处各画一支的话，早晚一支圆头一支方头。

    竖着构造（笔杆＝胶囊，笔尖＝三角），再整支绕自己中心转 [deg] 度。
    [cx],[cy] 是笔的中心，[length] 全长，[half] 杆半宽，[nib] 笔尖长度。
    """
    tf = rot_at(deg, cx, cy)
    top = cy - length / 2.0
    tip = cy + length / 2.0
    cap = top + half            # 胶囊帽的圆心
    collar = tip - nib          # 杆与尖的交界
    barrel = (Path(tf).M(cx - half, collar).L(cx - half, cap)
              .A(half, 0, 1, cx + half, cap).L(cx + half, collar).Z())
    return [S(barrel), S(poly([(cx - half, collar), (cx, tip), (cx + half, collar)], tf=tf))]


D45 = rot(45)


def mirror_x(x, y):
    """左右镜像（绕画布中轴）。撤销/重做这类成对的图标靠它保证两个逐点对称。"""
    return (2 * CENTER - x, y)


MIRROR_X = mirror_x


def ICONS():
    ic = {}

    # —— 导航 ——
    ic["chevron_left"] = Icon("上一页 / 返回：角标", [S(chevron(-1, 0))])
    ic["chevron_right"] = Icon("下一页 / 展开：角标", [S(chevron(1, 0))])
    ic["chevron_up"] = Icon("收起：角标", [S(chevron(0, -1))])
    ic["chevron_down"] = Icon("展开：角标", [S(chevron(0, 1))])
    ic["more"] = Icon("溢出菜单：三点", [F(circle(5.6, 12, 1.55)), F(circle(12, 12, 1.55)),
                                    F(circle(18.4, 12, 1.55))])
    ic["grip"] = Icon("拖动把手：两列三行圆点（浮条 / 顶栏里的一组键拖来拖去）", [
        F(circle(x, y, 1.55)) for x in (9.2, 14.8) for y in (5.6, 12, 18.4)])
    # —— 可自由编组的工具（原先只在 ⋯ 菜单里的那几项，2026-09-26 起也能摆上栏，得有图标） ——
    ic["grid4"] = Icon("画板笔记列表：四格", [
        S(rrect(4.4, 4.4, 10.8, 10.8, 1.6)), S(rrect(13.2, 4.4, 19.6, 10.8, 1.6)),
        S(rrect(4.4, 13.2, 10.8, 19.6, 1.6)), S(rrect(13.2, 13.2, 19.6, 19.6, 1.6))])
    ic["moon"] = Icon("夜间模式：弯月", [
        S(Path().M(17.5, 4.6).A(8, 1, 0, 17.5, 19.4).A(9, 0, 1, 17.5, 4.6).Z())])
    ic["two_finger"] = Icon("双指滚动（防误触）：两根并排的手指", [
        S(rrect(6.2, 6.0, 10.6, 19.6, 2.2)), S(rrect(13.4, 4.4, 17.8, 18.0, 2.2))])
    ic["h_lock"] = Icon("锁定水平滚动：双向箭头 + 两端挡板", [
        S(line(7.0, 12, 17.0, 12)), S(poly([(9.6, 9.2), (6.8, 12), (9.6, 14.8)])),
        S(poly([(14.4, 9.2), (17.2, 12), (14.4, 14.8)])),
        S(line(4.4, 7.6, 4.4, 16.4)), S(line(19.6, 7.6, 19.6, 16.4))])
    ic["layers"] = Icon("图层：两层菱形叠放", [
        S(poly([(12, 5.8), (19.6, 10.0), (12, 14.2), (4.4, 10.0)], close=True)),
        S(poly([(4.4, 14.0), (12, 18.2), (19.6, 14.0)]))])
    ic["hash"] = Icon("跳到第几页：#", [
        S(line(9.6, 4.6, 8.0, 19.4)), S(line(16.0, 4.6, 14.4, 19.4)),
        S(line(5.0, 9.2, 19.4, 9.2)), S(line(4.6, 14.8, 19.0, 14.8))])
    ic["plus"] = Icon("新建", [S(line(12, 4.2, 12, 19.8)), S(line(4.2, 12, 19.8, 12))])
    ic["close"] = Icon("关闭", [S(line(6.3, 6.3, 17.7, 17.7)), S(line(17.7, 6.3, 6.3, 17.7))],
                       span=(11.0, SPAN_MAX))
    ic["check"] = Icon("当前项 / 已选", [S(poly([(4.6, 12.4), (9.6, 17.4), (19.4, 6.4)]))])

    # —— 文档 ——
    ic["doc"] = Icon("文档：竖页 + 右上折角（与 scratch 的横板刻意不同形）", [
        S(Path().M(13.6, 2.6).L(6.6, 2.6).A(2, 0, 0, 4.6, 4.6).L(4.6, 19.4)
          .A(2, 0, 0, 6.6, 21.4).L(17.4, 21.4).A(2, 0, 0, 19.4, 19.4).L(19.4, 8.4).Z()),
        S(Path().M(13.6, 2.6).L(13.6, 8.4).L(19.4, 8.4)),
    ])
    # 书签：经典缎带轮廓（下缘一个 V 口）。与 Mac 页面上那面红缎带、web 的 bookmark
    # 图标同一个符号语言 —— 三端认得出是同一样东西（../REQUIREMENTS.md §1.9）。
    ic["bookmark"] = Icon("书签", [
        S(Path().M(6.2, 3.4).L(17.8, 3.4).L(17.8, 20.6).L(12, 16.2).L(6.2, 20.6).Z()),
    ])
    ic["list"] = Icon("列表 / 目录", [
        S(line(9.3, 6.6, 19.25, 6.6)), S(line(9.3, 12, 19.25, 12)), S(line(9.3, 17.4, 19.25, 17.4)),
        F(circle(5.0, 6.6, 1.15)), F(circle(5.0, 12, 1.15)), F(circle(5.0, 17.4, 1.15)),
    ])
    ic["folder"] = Icon("目录 / 工作区", [
        S(Path().M(3.4, 6.6).A(2, 0, 1, 5.4, 4.6).L(9.2, 4.6).L(11.6, 7.4).L(18.6, 7.4)
          .A(2, 0, 1, 20.6, 9.4).L(20.6, 17.4).A(2, 0, 1, 18.6, 19.4).L(5.4, 19.4)
          .A(2, 0, 1, 3.4, 17.4).Z()),
    ])
    ic["book"] = Icon("书库", [
        S(Path().M(7.3, 2.8).L(19.5, 2.8).L(19.5, 21.2).L(7.3, 21.2)
          .A(2.8, 0, 1, 4.5, 18.4).L(4.5, 5.6).A(2.8, 0, 1, 7.3, 2.8).Z()),
        S(Path().M(4.5, 18.4).A(2.8, 0, 1, 7.3, 15.6).L(19.5, 15.6)),
    ])
    ic["text"] = Icon("文字 / 改名：T", [
        S(line(4.8, 5.6, 19.2, 5.6)), S(line(12, 5.6, 12, 18.4)), S(line(8.6, 18.4, 15.4, 18.4)),
    ])
    ic["delete"] = Icon("删除：垃圾桶", [
        S(line(4.4, 6.6, 19.6, 6.6)),
        S(Path().M(9.4, 6.6).L(9.4, 4.6).A(1.6, 0, 1, 11, 3).L(13, 3).A(1.6, 0, 1, 14.6, 4.6).L(14.6, 6.6)),
        S(Path().M(6.6, 6.6).L(7.5, 19.6).A(1.9, 0, 0, 9.4, 21.4).L(14.6, 21.4)
          .A(1.9, 0, 0, 16.5, 19.6).L(17.4, 6.6)),
        S(line(10.2, 10.6, 10.2, 17.6)), S(line(13.8, 10.6, 13.8, 17.6)),
    ])

    # —— 设备 / 状态 ——
    ic["tablet"] = Icon("模式2 入口：平板", [
        S(rrect(5.2, 2.4, 18.8, 21.6, 2.2)), S(line(10, 18.6, 14, 18.6)),
    ])
    ic["monitor"] = Icon("Mac / 第二屏", [
        S(rrect(2.4, 3.8, 21.6, 17.0, 2.0)), S(line(12, 17.0, 12, 20.2)), S(line(8, 20.2, 16, 20.2)),
    ])
    # ic["sync"]（「跟随 Mac」）2026-08-28 随 PadDocsPicker 一起删了——模式2 改用与模式1 同一条
    # 标签页栏，「开着哪几篇 / 当前是哪篇」直接看栏上，不再需要一个跟随态图标。几何见下面的
    # circ_arrow（留着：缺口圆 + 箭头是通用件，下次要画「刷新 / 重连」这类图标直接用）。
    ic["eye"] = Icon("图层可见", [
        S(Path().M(2.4, 12).C(5.4, 6.2, 8.6, 5.3, 12, 5.3).C(15.4, 5.3, 18.6, 6.2, 21.6, 12)
          .C(18.6, 17.8, 15.4, 18.7, 12, 18.7).C(8.6, 18.7, 5.4, 17.8, 2.4, 12).Z()),
        S(circle(12, 12, 3.3)),
    ])
    ic["eye_off"] = Icon("图层隐藏", [
        S(Path().M(2.4, 12).C(5.4, 6.2, 8.6, 5.3, 12, 5.3).C(15.4, 5.3, 18.6, 6.2, 21.6, 12)
          .C(18.6, 17.8, 15.4, 18.7, 12, 18.7).C(8.6, 18.7, 5.4, 17.8, 2.4, 12).Z()),
        S(circle(12, 12, 3.3)),
        S(line(3.9, 3.9, 20.1, 20.1)),
    ])
    ic["info"] = Icon("提示：圆 + 一点一竖（界面上那些一句话小提示的标记）", [
        S(circle(12, 12, 8.6)),
        F(circle(12, 7.9, 1.15)),
        S(line(12, 11.3, 12, 16.6)),
    ])
    ic["lock"] = Icon("锁定缩放：挂锁", [
        S(rrect(4.6, 10.2, 19.4, 20.8, 2.2)),
        S(Path().M(8, 10.2).L(8, 7.2).A(4, 0, 1, 16, 7.2).L(16, 10.2)),
    ])
    ic["write_lock"] = Icon("书写锁定：笔（同 pen 那一支）+ 右下角一把小锁——与「锁定缩放」的整把挂锁一眼分得开",
                            pen_shape(9.4, 10.0, 16.4, 2.5, 6.0) + [
        S(rrect(13.2, 15.0, 20.8, 20.8, 1.4)),
        S(Path().M(15.0, 15.0).L(15.0, 13.4).A(2.0, 0, 1, 19.0, 13.4).L(19.0, 15.0)),
    ])
    ic["relative_width"] = Icon("相对粗细模式：放大镜（缩放）+ 里面一点墨迹", [
        S(circle(10, 10, 6.2)),
        S(line(14.4, 14.4, 19.6, 19.6)),
        F(circle(10, 10, 1.6)),
    ])

    # —— 笔与画布（顶栏那几个键的重头戏）——
    ic["pen"] = Icon("切换笔：笔杆 + 笔尖，整支 45° 摆放（会被染成当前笔色）",
                     pen_shape(11.38, 12.62, 23.5, 4.2, 10.6))
    ic["ruler"] = Icon("尺子（45° 吸附）：刻度尺，长短刻度交替", [
        S(rrect(8.6, 2.6, 15.4, 21.4, 1.6, D45)),
        S(line(8.6, 6.0, 12.0, 6.0, D45)), S(line(8.6, 10.0, 10.8, 10.0, D45)),
        S(line(8.6, 14.0, 12.0, 14.0, D45)), S(line(8.6, 18.0, 10.8, 18.0, D45)),
    ])
    ic["scratch"] = Icon("草稿纸：板 + 笔（笔与 pen 是同一支）。板右上角留缺口给笔穿过，"
                         "所以它跟竖页折角的 doc 一眼就分得开", [
        S(Path().M(17.0, 13.0).L(17.0, 18.4).A(2.4, 0, 1, 14.6, 20.8).L(5.0, 20.8)
          .A(2.4, 0, 1, 2.6, 18.4).L(2.6, 8.8).A(2.4, 0, 1, 5.0, 6.4).L(10.4, 6.4)),
    ] + pen_shape(16.1, 8.3, 12.7, 2.3, 5.2))
    ic["canvas"] = Icon("画板模式：页面 + 两侧向外的箭头（页两边的空白也能写字 = 横向摊开）", [
        S(rrect(9.3, 3.9, 14.7, 20.1, 1.4)),
        S(line(7.3, 12.0, 2.9, 12.0)),
        S(poly([(5.1, 9.8), (2.7, 12.0), (5.1, 14.2)])),
        S(line(16.7, 12.0, 21.1, 12.0)),
        S(poly([(18.9, 9.8), (21.3, 12.0), (18.9, 14.2)])),
    ])
    ic["paper"] = Icon("纸样（空白/横线/方格/点阵）：方格", [
        S(rrect(3.4, 3.4, 20.6, 20.6, 2.4)),
        S(line(3.4, 9.15, 20.6, 9.15)), S(line(3.4, 14.85, 20.6, 14.85)),
        S(line(9.15, 3.4, 9.15, 20.6)), S(line(14.85, 3.4, 14.85, 20.6)),
    ])
    ic["fit"] = Icon("适应内容：四角括号", [
        S(Path().M(3.6, 8.2).L(3.6, 5.4).A(1.8, 0, 1, 5.4, 3.6).L(8.2, 3.6)),
        S(Path().M(15.8, 3.6).L(18.6, 3.6).A(1.8, 0, 1, 20.4, 5.4).L(20.4, 8.2)),
        S(Path().M(20.4, 15.8).L(20.4, 18.6).A(1.8, 0, 1, 18.6, 20.4).L(15.8, 20.4)),
        S(Path().M(8.2, 20.4).L(5.4, 20.4).A(1.8, 0, 1, 3.6, 18.6).L(3.6, 15.8)),
    ])
    ic["scope"] = Icon("回中：准星", [
        S(circle(12, 12, 6.6)),
        S(line(12, 2.6, 12, 5.2)), S(line(12, 18.8, 12, 21.4)),
        S(line(2.6, 12, 5.2, 12)), S(line(18.8, 12, 21.4, 12)),
        F(circle(12, 12, 1.3)),
    ])
    # —— 模式键（顶栏那颗随当前模式换图标的键，见 TopBar.modeIcon）——
    # 四个图标必须彼此一眼可分，且**都不能跟栏上别的键撞**：
    # 笔用铅笔（平顶 + 箍圈），不用 `pen` 那支圆帽马克笔——它俩在笔模式下是邻居；
    # 框选用虚线绳圈，不用四角括号——那是 `fit`（适应内容）。
    PENCIL = rot_move(45, 12, 12, -0.92, 0.92)
    ic["mode_pen"] = Icon("笔记模式：铅笔（平顶 + 箍圈，区别于 pen 那支圆帽马克笔）", [
        S(poly([(8.2, 14.6), (8.2, 2.6), (15.8, 2.6), (15.8, 14.6)], close=True, tf=PENCIL)),
        S(line(8.2, 5.9, 15.8, 5.9, PENCIL)),
        S(poly([(8.2, 14.6), (12, 22.6), (15.8, 14.6)], tf=PENCIL)),
    ])
    ERASER = rot(-45, 12, 11.6)
    ic["mode_eraser"] = Icon("擦除模式：斜置橡皮块 + 桌面线", [
        S(rrect(5.5, 7.6, 18.5, 15.6, 1.7, ERASER)),
        S(line(10.1, 7.6, 10.1, 15.6, ERASER)),
        S(line(4.4, 19.82, 19.6, 19.82)),
    ])
    ic["mode_pan"] = Icon("翻页模式：四向箭头（单指划动＝平移页面）", [
        S(line(12, 3.6, 12, 20.4)), S(line(3.6, 12, 20.4, 12)),
        S(poly([(9.8, 6.0), (12, 3.6), (14.2, 6.0)])),
        S(poly([(9.8, 18.0), (12, 20.4), (14.2, 18.0)])),
        S(poly([(6.0, 9.8), (3.6, 12), (6.0, 14.2)])),
        S(poly([(18.0, 9.8), (20.4, 12), (18.0, 14.2)])),
    ])
    # —— 编辑：撤销/重做 + 剪贴板（2026-09-02；2026-09-06 重画）——
    # 撤销/重做是一对**镜像**：↩ = 撤销，↪ = 重做（`MIRROR_X` 保证两个一模一样，不会一个胖一个瘦）。
    # 🔴 首版画的是「一个几乎闭合的大圆环 + 上方一个小箭头」，19dp 下读起来是"刷新/重置"——
    # 用户 2026-09-06 在模式2 顶栏报「尺子右边多了两个图标，不知道干啥的」。现在是通行的
    # 弯钩箭头：一横 + 右端 180° 回转 + 左端左指箭头，一眼就是「往回走一步」。
    ic["undo"] = Icon("撤销：↩ 弯钩箭头（横杆向右、右端回转向下、箭头在左端指左）", [
        # 横杆 y=9.1 从 x=4.8 到 14.6；右端半圆 r=4.6（圆心 14.6,13.7，向右鼓到 x=19.2）；
        # 回来的那截短一些（到 9.6）——两截等长会读成一个闭合的 U，看不出是箭头绕回来的
        S(Path().M(4.8, 9.1).L(14.6, 9.1).A(4.6, 0, 1, 14.6, 18.3).L(9.6, 18.3)),
        S(poly([(8.2, 5.7), (4.8, 9.1), (8.2, 12.5)])),      # 左端箭头（对称于横杆）
    ])
    ic["redo"] = Icon("重做：↪ 弯钩箭头（undo 的逐点镜像）", [
        # ⚠️ 镜像会把圆弧的绕行方向也翻过来：sweep 必须跟着从 1 改成 0，否则弧会朝反方向鼓出画布
        S(Path(MIRROR_X).M(4.8, 9.1).L(14.6, 9.1).A(4.6, 0, 0, 14.6, 18.3).L(9.6, 18.3)),
        S(poly([(8.2, 5.7), (4.8, 9.1), (8.2, 12.5)], tf=MIRROR_X)),
    ])
    ic["cut"] = Icon("剪切：剪刀（两个把手环 + 交叉刃）", [
        S(circle(6.6, 18.0, 2.6)), S(circle(17.4, 18.0, 2.6)),
        S(line(16.2, 3.4, 8.0, 15.6)), S(line(7.8, 3.4, 16.0, 15.6)),
    ])
    ic["copy"] = Icon("复制：两页叠放（后一页只露左上两条边）", [
        S(rrect(8.6, 8.6, 20.4, 20.4, 1.8)),
        S(Path().M(15.4, 5.6).L(15.4, 4.6).A(1.8, 0, 0, 13.6, 3.6).L(5.4, 3.6)
          .A(1.8, 0, 0, 3.6, 5.4).L(3.6, 13.6).A(1.8, 0, 0, 5.4, 15.4).L(6.4, 15.4)),
    ])
    ic["paste"] = Icon("粘贴：写字板 + 顶上的夹子", [
        S(rrect(4.6, 4.6, 19.4, 21.4, 1.8)),
        S(rrect(8.8, 2.6, 15.2, 6.6, 1.2)),
    ])

    ic["mode_lasso"] = Icon("框选模式：虚线绳圈 + 尾（自由框选，不是矩形选框）", [
        S(Path().M(11.6, 3.4).C(14.9, 3.3, 17.6, 4.2, 19.4, 6.2)),
        S(Path().M(20.6, 9.4).C(20.9, 11.2, 20.4, 13.0, 19.2, 14.4)),
        S(Path().M(16.4, 16.4).C(15.2, 16.8, 14.0, 16.9, 12.8, 16.9)),
        S(Path().M(9.8, 16.5).C(7.2, 16.0, 5.1, 14.7, 3.9, 12.8)),
        S(Path().M(3.5, 9.6).C(4.0, 6.9, 6.4, 4.5, 9.4, 3.7)),
        S(Path().M(12.8, 16.9).C(11.3, 17.9, 11.1, 19.6, 12.3, 20.8)),
    ])

    # 选字模式（模式1 专属的第五档）：I 形文本光标 + 底下那条被选中的文字。
    # 光标形状就是 macOS 划字时的那个指针 —— 「和 macOS 端对齐」这件事从图标就开始。
    # 只画光标不画底线的话，在这个尺寸下容易被读成尺子/竖条。
    ic["mode_text"] = Icon("选字模式：I 形文本光标 + 一行文字", [
        S(line(12, 4.4, 12, 17.0)),
        S(line(9.2, 4.4, 14.8, 4.4)),
        S(line(9.2, 17.0, 14.8, 17.0)),
        S(line(4.4, 20.2, 19.6, 20.2)),
    ])

    ic["map"] = Icon("缩略图：外框 + 实心视口（实心是为了跟 scratch 的波纹拉开）", [
        S(rrect(2.6, 5.2, 21.4, 18.8, 2.2)),
        F(rrect(13.2, 12.0, 18.6, 16.4, 0.9)),
    ])
    return ic


# ───────────────────────── 校验 + 落盘 ─────────────────────────

def bbox(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return (min(xs), min(ys), max(xs), max(ys))


def measure(icon):
    inner = None
    outer = None
    for sh in icon.shapes:
        b = bbox(sh.path.points())
        pad = STROKE / 2.0 if sh.kind == "stroke" else 0.0
        o = (b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
        inner = b if inner is None else (min(inner[0], b[0]), min(inner[1], b[1]),
                                         max(inner[2], b[2]), max(inner[3], b[3]))
        outer = o if outer is None else (min(outer[0], o[0]), min(outer[1], o[1]),
                                         max(outer[2], o[2]), max(outer[3], o[3]))
    return inner, outer


def check(name, icon):
    inner, outer = measure(icon)
    errs = []
    if inner[0] < LIVE[0] - 1e-6 or inner[1] < LIVE[0] - 1e-6 or \
       inner[2] > LIVE[1] + 1e-6 or inner[3] > LIVE[1] + 1e-6:
        errs.append("内容超出活动区 %s：%s" % (LIVE, fmt(inner)))
    if outer[0] < SAFE[0] or outer[1] < SAFE[0] or outer[2] > SAFE[1] or outer[3] > SAFE[1]:
        errs.append("描边外沿会被画布裁掉：%s" % fmt(outer))
    cx = (outer[0] + outer[2]) / 2.0
    cy = (outer[1] + outer[3]) / 2.0
    if abs(cx - CENTER) > CENTER_TOL or abs(cy - CENTER) > CENTER_TOL:
        errs.append("重心偏了：(%.2f, %.2f)" % (cx, cy))
    span = max(outer[2] - outer[0], outer[3] - outer[1]) - STROKE
    if span < icon.span[0] - 1e-6 or span > icon.span[1] + 1e-6:
        errs.append("视觉尺寸 %.2f 不在 %s 内（跟别的图标不是一个大小）" % (span, icon.span))
    return errs, inner, outer, span


def fmt(b):
    return "[%.2f,%.2f]-[%.2f,%.2f]" % b


HEAD = u"""<?xml version="1.0" encoding="utf-8"?>
<!--
  **生成物，勿手改** —— 改 `tools/icons/gen.py` 后重跑 `python3 tools/icons/gen.py`。
  {note}
  规格：24 画布 / 内容活动区 20 / 描边 1.8 round / 无投影渐变。
  颜色恒为白，真实颜色由代码 imageTintList 给（语义色，跟随深浅色）。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
"""

STROKE_PATH = u"""    <path
        android:pathData="{d}"
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{w}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
"""

FILL_PATH = u"""    <path
        android:pathData="{d}"
        android:fillColor="#FFFFFFFF" />
"""


def xml_for(icon):
    # 同属性的子路径合并成一条 <path>：inflate 时少建对象，也少一处能飘的地方
    strokes = " ".join(s.path.data() for s in icon.shapes if s.kind == "stroke")
    fills = " ".join(s.path.data() for s in icon.shapes if s.kind == "fill")
    out = HEAD.format(note=icon.note)
    if strokes:
        out += STROKE_PATH.format(d=strokes, w=_n(STROKE))
    if fills:
        out += FILL_PATH.format(d=fills)
    return out + u"</vector>\n"


def refs():
    """扫一遍 Kotlin 源里引用了哪些 `R.drawable.ic_*`。

    生成器同时是**唯一的清单**：这里对不上就说明要么删了还有人用（编译期才报错），
    要么画了没人用（悄悄堆在 res 里，跟着每个 APK 走）。都在这一步挡掉。
    """
    import re
    used = set()
    src = os.path.join(ROOT, "app", "src")
    for base, _dirs, files in os.walk(src):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            with io.open(os.path.join(base, fn), encoding="utf-8") as f:
                for m in re.finditer(r"R\.drawable\.ic_([a-z0-9_]+)", f.read()):
                    used.add(m.group(1))
    return used


def main():
    args = sys.argv[1:]
    dry = "--check" in args
    icons = ICONS()
    bad = 0
    print("%-16s %-26s %-26s %6s" % ("图标", "内容 bbox", "含描边 bbox", "尺寸"))
    print("-" * 80)
    for name in sorted(icons):
        errs, inner, outer, span = check(name, icons[name])
        print("%-16s %-26s %-26s %6.2f %s" % (name, fmt(inner), fmt(outer), span,
                                              "" if not errs else "  ✗"))
        for e in errs:
            print("    ✗ %s" % e)
            bad += 1
    print("-" * 80)
    used = refs()
    have = set(icons)
    for n in sorted(used - have):
        print("✗ 代码里用了 ic_%s，但这里没画" % n)
        bad += 1
    for n in sorted(have - used):
        print("✗ 画了 ic_%s，但代码里没人用（别让它跟着 APK 走）" % n)
        bad += 1
    stale = set()
    if os.path.isdir(OUT):
        for fn in os.listdir(OUT):
            if fn.startswith("ic_") and fn.endswith(".xml") and fn[3:-4] not in have:
                stale.add(fn)
    for fn in sorted(stale):
        print("✗ res 里还留着 %s，不是本文件的生成物（手工残留，删掉）" % fn)
        bad += 1
    if bad:
        print("%d 项不合规，未写盘" % bad)
        return 1
    if dry:
        print("校验通过（%d 个图标），--check 不写盘" % len(icons))
        return 0
    for name in sorted(icons):
        path = os.path.join(OUT, "ic_%s.xml" % name)
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(xml_for(icons[name]))
    print("已生成 %d 个图标 → %s" % (len(icons), os.path.relpath(OUT, ROOT)))
    if "--sheet" in args:
        sheet(icons)
    return 0


def sheet(icons, out=None):
    """对照大图：一次看全 28 个，检查是不是「一套」——线宽、重量、留白。"""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib.patches import Polygon
    except ImportError:
        print("（跳过对照图：没有 matplotlib）")
        return
    plt.rcParams["font.sans-serif"] = ["Hiragino Sans GB", "Arial Unicode MS", "PingFang SC"]
    names = sorted(icons)
    cols = 7
    rows = (len(names) + cols - 1) // cols
    cell = 1.1
    fig, axes = plt.subplots(rows, cols, figsize=(cols * cell, rows * cell * 1.18))
    lw = STROKE * (cell * 72.0 / VIEW) * 0.86
    for i, ax in enumerate(axes.ravel()):
        ax.set_xlim(0, VIEW)
        ax.set_ylim(VIEW, 0)
        ax.set_aspect("equal")
        ax.axis("off")
        if i >= len(names):
            continue
        name = names[i]
        ax.add_patch(plt.Rectangle((LIVE[0], LIVE[0]), LIVE[1] - LIVE[0], LIVE[1] - LIVE[0],
                                   fill=False, ec="#E3E7EE", lw=0.5))
        for sh in icons[name].shapes:
            for seg in sh.path.subpaths():
                xs = [p[0] for p in seg]
                ys = [p[1] for p in seg]
                if sh.kind == "fill":
                    ax.add_patch(Polygon(list(zip(xs, ys)), closed=True, fc="#14181D", ec="none"))
                else:
                    ax.plot(xs, ys, color="#14181D", lw=lw, solid_capstyle="round",
                            solid_joinstyle="round")
        ax.set_title(name, fontsize=6, color="#5B6472", pad=2)
    fig.tight_layout()
    out = out or os.path.join(os.path.dirname(os.path.abspath(__file__)), "sheet.png")
    fig.savefig(out, dpi=220, facecolor="white")
    print("对照图 → %s" % out)


if __name__ == "__main__":
    sys.exit(main())
