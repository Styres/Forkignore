"""Python 孪生管线回归门禁 (CI 入口)。

历史沿革: 早期此脚本独立复刻了一遍"负样本拦截 + 正样本逐格 FEN 比对"流程;
自 comb_locator/test_full_pipeline_v2 收敛为 grid_calibrate.locate_board 的
薄委托后, 它与 verify_ground_truth_diff 对同一批帧跑完全相同的慢速精标定
管线 (CI 双倍耗时且零增量信息), 故直接复用同一入口, 保证单一真源。

注意语义边界: 此门禁验证的是 Python 孪生管线与人工真值的一致性;
Kotlin 运行时的真实 parity 以阶段四真机 Confidence/Residual 遥测为准。
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def run_parity_verification():
    from tools.verify_ground_truth_diff import run_ground_truth_diff_verification
    run_ground_truth_diff_verification()


if __name__ == '__main__':
    run_parity_verification()
