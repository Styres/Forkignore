import cv2
import numpy as np
import os
import glob

def extract_feature(cell_gray):
    # 48x48
    resized = cv2.resize(cell_gray, (48, 48))
    # 30x30 core ROI (9..38, 9..38)
    core = resized[9:39, 9:39].astype(np.float32)
    
    # Sobel
    gx = cv2.Sobel(core, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(core, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.sqrt(gx**2 + gy**2)
    
    norm = np.linalg.norm(mag)
    mag_norm = mag / (norm + 1e-5)
    
    return {
        'mag_norm': mag_norm,
        'center_std': float(np.std(core)),
        'center_mean': float(np.mean(core)),
        'grad_mean': float(np.mean(mag))
    }

def locate_board_two_stage(image):
    h, w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    
    min_size = int(w * 0.82)
    max_size = min(w - 2, int(w * 0.98))
    
    best_score = -1e9
    best_size = min_size
    best_y = 0
    
    # Stage 1: Coarse
    for size in range(min_size, max_size + 1, 8):
        x = (w - size) // 2
        for y in range(0, h - size, 8):
            step = size / 8.0
            h_peak = 0.0
            h_valley = 0.0
            for i in range(1, 8):
                ly = int(y + i * step)
                my = int(y + (i - 0.5) * step)
                if 0 <= ly < h:
                    h_peak += np.mean(gy[ly, x:x+size])
                if 0 <= my < h:
                    h_valley += np.mean(gy[my, x:x+size])
                    
            corr_sum = 0.0
            grid_means = np.zeros(64)
            for r in range(8):
                cy1 = int(y + r * step)
                cy2 = int(y + (r + 1) * step)
                for c in range(8):
                    cx1 = int(x + c * step)
                    cx2 = int(x + (c + 1) * step)
                    cell = gray[cy1:cy2, cx1:cx2]
                    grid_means[r*8+c] = np.mean(cell) if cell.size > 0 else 0
            
            mean_val = np.mean(grid_means)
            for r in range(8):
                for c in range(8):
                    pat = 1.0 if (r+c)%2 == 0 else -1.0
                    corr_sum += (grid_means[r*8+c] - mean_val) * pat
                    
            score = (h_peak - h_valley * 0.7) * 0.8 + abs(corr_sum) * 1.5
            if score > best_score:
                best_score = score
                best_size = size
                best_y = y
                
    # Stage 2: Fine (in best_size +- 16, best_y +- 16)
    fine_size = best_size
    fine_y = best_y
    exact_score = best_score
    
    for size in range(max(min_size, best_size - 16), min(max_size, best_size + 16) + 1, 2):
        x = (w - size) // 2
        for y in range(max(0, best_y - 16), min(h - size, best_y + 16) + 1, 2):
            step = size / 8.0
            h_peak = 0.0
            h_valley = 0.0
            for i in range(1, 8):
                ly = int(y + i * step)
                my = int(y + (i - 0.5) * step)
                if 0 <= ly < h:
                    h_peak += np.mean(gy[ly, x:x+size])
                if 0 <= my < h:
                    h_valley += np.mean(gy[my, x:x+size])
                    
            score = (h_peak - h_valley * 0.7) * 0.8
            if score > exact_score:
                exact_score = score
                fine_size = size
                fine_y = y
                
    fx = (w - fine_size) // 2
    return fx, fine_y, fx + fine_size, fine_y + fine_size

def run_verification():
    # 1. Load exported templates
    template_dir = "android_copilot/app/src/main/assets/templates"
    template_files = glob.glob(os.path.join(template_dir, "*.png"))
    templates = []
    for tf in template_files:
        cls_name = os.path.basename(tf).split("_")[0].upper()
        img = cv2.imread(tf, cv2.IMREAD_GRAYSCALE)
        feat = extract_feature(img)
        templates.append((cls_name, feat['mag_norm']))
        
    print(f"Loaded {len(templates)} templates from {template_dir}")
    
    test_images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]
    
    for img_name in test_images:
        print(f"\n=================== Testing: {img_name} ===================")
        img = cv2.imread(img_name)
        if img is None:
            print(f"Could not load {img_name}")
            continue
            
        l, t, r, b = locate_board_two_stage(img)
        print(f"Located Board Rect: [{l}, {t}, {r}, {b}], size={r-l}x{b-t}")
        
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        step = (r - l) / 8.0
        
        cells_feat = []
        occupied = []
        
        for row in range(8):
            row_feats = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                
                cell = gray[y1:y2, x1:x2]
                f = extract_feature(cell)
                row_feats.append(f)
                
                if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
                    best_cls = 'P'
                    best_sim = -1e9
                    for t_cls, t_mag in templates:
                        sim = float(np.sum(f['mag_norm'] * t_mag))
                        if sim > best_sim:
                            best_sim = sim
                            best_cls = t_cls
                    occupied.append((row, col, f, best_cls))
            cells_feat.append(row_feats)
            
        # 2-Means
        raw_board = [['.' for _ in range(8)] for _ in range(8)]
        if occupied:
            means = [item[2]['center_mean'] for item in occupied]
            c1, c2 = min(means), max(means)
            for _ in range(10):
                g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
                g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
                if g1: c1 = float(np.mean(g1))
                if g2: c2 = float(np.mean(g2))
            thresh = (c1 + c2) / 2.0
            
            for r, c, f, cls_name in occupied:
                is_white = f['center_mean'] >= thresh
                raw_board[r][c] = cls_name.upper() if is_white else cls_name.lower()
                
        # Perspective
        top_w = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].isupper())
        top_b = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].islower())
        bot_w = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].isupper())
        bot_b = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].islower())
        
        is_white_persp = bot_w >= bot_b
        print(f"Perspective: {'White (执白)' if is_white_persp else 'Black (执黑)'}")
        
        if is_white_persp:
            std_board = raw_board
            active = 'w'
        else:
            std_board = [row[::-1] for row in raw_board[::-1]]
            active = 'b'
            
        print("Raw Screen Board:")
        for row in raw_board:
            print(" ".join(row))
            
        fen_rows = []
        for row in std_board:
            row_str = ''
            empty = 0
            for s in row:
                if s == '.':
                    empty += 1
                else:
                    if empty > 0:
                        row_str += str(empty)
                        empty = 0
                    row_str += s
            if empty > 0:
                row_str += str(empty)
            fen_rows.append(row_str)
            
        board_fen = "/".join(fen_rows)
        full_fen = f"{board_fen} {active} KQkq - 0 1"
        print(f"Standard Board FEN: {board_fen}")
        print(f"Full FEN: {full_fen}")

if __name__ == '__main__':
    run_verification()
