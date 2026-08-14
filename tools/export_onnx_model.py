import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np
import os
import onnxruntime as ort

class DuolingoChessPieceNet(nn.Module):
    """
    轻量端侧多邻国棋子分类网络 (DuolingoChessPieceNet)
    输入: 64 个格子的核心 ROI (64, 1, 32, 32)
    输出: 
      1. is_empty_logits (64, 2)
      2. piece_class_logits (64, 6) -> [P, R, N, B, Q, K]
      3. is_white_logits (64, 2)
    """
    def __init__(self):
        super(DuolingoChessPieceNet, self).__init__()
        
        # 特征提取卷积层
        self.conv1 = nn.Conv2d(1, 16, kernel_size=3, padding=1)
        self.pool1 = nn.MaxPool2d(2, 2) # 16x16
        self.conv2 = nn.Conv2d(16, 32, kernel_size=3, padding=1)
        self.pool2 = nn.MaxPool2d(2, 2) # 8x8
        
        self.fc_shared = nn.Linear(32 * 8 * 8, 128)
        
        # 3 个分支头
        self.head_empty = nn.Linear(128, 2)
        self.head_class = nn.Linear(128, 6) # P, R, N, B, Q, K
        self.head_color = nn.Linear(128, 2) # black, white

    def forward(self, x):
        # x: (N, 1, 32, 32)
        feat = F.relu(self.conv1(x))
        feat = self.pool1(feat)
        feat = F.relu(self.conv2(feat))
        feat = self.pool2(feat)
        feat = feat.view(feat.size(0), -1)
        shared = F.relu(self.fc_shared(feat))
        
        out_empty = self.head_empty(shared)
        out_class = self.head_class(shared)
        out_color = self.head_color(shared)
        
        return out_empty, out_class, out_color

# 实例化并初始化权重
os.makedirs("models", exist_ok=True)
model = DuolingoChessPieceNet()
model.eval()

# 导出 ONNX 模型
dummy_input = torch.randn(64, 1, 32, 32, dtype=torch.float32)
onnx_path = "models/duolingo_chess.onnx"

torch.onnx.export(
    model,
    dummy_input,
    onnx_path,
    input_names=["cell_images"],
    output_names=["out_empty", "out_class", "out_color"],
    dynamic_axes={
        "cell_images": {0: "batch_size"},
        "out_empty": {0: "batch_size"},
        "out_class": {0: "batch_size"},
        "out_color": {0: "batch_size"}
    },
    opset_version=14
)

print(f"成功导出 ONNX 模型至: {onnx_path}")

# 使用 onnxruntime 验证加载与推理
session = ort.InferenceSession(onnx_path)
test_in = np.random.randn(64, 1, 32, 32).astype(np.float32)
outputs = session.run(None, {"cell_images": test_in})

print(f"ONNXRuntime 验证成功: 输出数量 = {len(outputs)}")
print(f"out_empty shape: {outputs[0].shape}, out_class shape: {outputs[1].shape}, out_color shape: {outputs[2].shape}")
