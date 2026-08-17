# -*- coding: utf-8 -*-
"""格线直测精标定回归验证 (阶段一门禁脚本)。

基准集为用户指定的 5 帧多邻国对局截图, 真值经独立实测 + 人工目检确认
(2026-08-17): 覆盖带边距 (duolingo_1)、满宽 (duolingo_2/bug_16/bug_17)、
负边距裁剪 (bug_20_lowsim) 三类布局, 以及机器人/真人对局的两种 y 位置。

断言 (测试用例):
1. 定位缺失 → 直接失败 (不允许返回 None 静默通过);
2. |x0 误差|, |y0 误差|, |size 误差| 均 <= TOL_PX (4px);
3. 负边距裁剪帧 (rect 越出图像边界) 必须被识别: 置信度不得为 high
   (high 保留给全框可见且双重证据通过的棋盘, 裁剪帧无法完整验证,
   只能提示而非硬匹配); 未越界的帧不做此项限制。

设计约定:
- 基准图缺失时响亮失败 (FileNotFoundError), 供 CI 门禁直接接入, 不静默跳过;
- 真值以 (x0, y0, size) 整数三元组固化, 换机型/换 UI 时按同流程
  (实测 + 目检) 追加新帧, 而非修改算法参数。
"""
import os
import sys
import time
from typing import Dict, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grid_calibrate import load_image, locate_board  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOL_PX = 4.0

Rect = Tuple[int, int, int]

# 真值目录: 文件 -> (x0, y0, size, 布局说明)。
# bug_20_lowsim 的 size=1264 > 屏宽 1260, 是负边距裁剪帧的真值形态。
GROUND_TRUTH: Dict[str, Tuple[Rect, str]] = {
    'duolingo_1.jpeg':       ((36, 459, 677),  '带边距'),
    'duolingo_2.jpg':        ((0, 719, 1179),  '满宽'),
    'bug_16.jpg':            ((0, 1029, 1260), '满宽'),
    'bug_17.jpg':            ((0, 1029, 1260), '满宽'),
    'bug_20_lowsim.jpg':     ((-4, 989, 1264), '负边距裁剪'),
}


def is_cropped(rect: Rect, img_w: int, img_h: int) -> bool:
    """棋盘框越出图像边界即判为裁剪帧 (负边距情形)。"""
    x0, y0, size = rect
    return x0 < 0 or y0 < 0 or x0 + size > img_w or y0 + size > img_h


def check_frame(name: str) -> Optional[str]:
    """单帧验证, 返回 None 表示通过, 否则返回失败原因字符串。"""
    path = os.path.join(BASE, name)
    if not os.path.exists(path):
        # 响亮失败: CI 中基准图缺失必须暴露, 不允许静默跳过
        raise FileNotFoundError(f'基准图缺失: {path}')
    truth, note = GROUND_TRUTH[name]
    img = load_image(path)
    img_h, img_w = img.shape[:2]

    t0 = time.time()
    res = locate_board(img, top_n=3)
    cost = time.time() - t0
    if res is None:
        return f'{name}: 定位失败 (无候选) [{note}]'

    rect = res['rect']
    errs = [f'{axis}: 得{got} 真{tru} 差{got - tru:+d}'
            for axis, got, tru in zip(('x0', 'y0', 'size'), rect, truth)
            if abs(got - tru) > TOL_PX]

    cropped = is_cropped(rect, img_w, img_h)
    # 裁剪帧只能"识别并提示": 框越界意味着格线证据不完整,
    # 若算法仍给 high 说明它在硬匹配, 违背负边距场景契约
    if cropped and res['confidence'] == 'high':
        errs.append(f'裁剪帧被标记 high 置信 (应为提示而非硬匹配)')

    status = ('PASS' if not errs else 'FAIL')
    crop_tag = ' [裁剪帧-已识别]' if cropped else ''
    print(f'{status}  {name:24s} rect={rect} conf={res["confidence"]}'
          f'{crop_tag}  resid={res["residual"]:.2f}  {cost:.1f}s  [{note}]')
    for e in errs:
        print(f'      -> {e}')
    return f'{name}: ' + '; '.join(errs) if errs else None


def main() -> int:
    print(f'格线直测精标定回归验证 (容差 {TOL_PX:.0f}px, {len(GROUND_TRUTH)} 帧)')
    failures = []
    for name in GROUND_TRUTH:
        try:
            err = check_frame(name)
        except FileNotFoundError as exc:
            print(f'FAIL  {exc}')
            return 2
        if err:
            failures.append(err)
    print()
    if failures:
        print(f'结果: {len(failures)}/{len(GROUND_TRUTH)} 帧未通过')
        for f in failures:
            print(f'  - {f}')
        return 1
    print(f'结果: {len(GROUND_TRUTH)}/{len(GROUND_TRUTH)} 帧全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
