import urllib.request
import zipfile
import io
import os

# V9 (bug_10 真机取证): 引擎缺 NNUE 评估网络时会在首次搜索时自行终止。
# 默认网络文件名由引擎二进制的报错信息给出，必须与编译期内置名严格一致。
NNUE_FILES = {
    'nn-5af11540bbfe.nnue': 'android_copilot/app/src/main/assets/nnue/nn-5af11540bbfe.nnue',
}
NNUE_DIRECT_URL_TMPL = 'https://tests.stockfishchess.org/api/nn/{name}'

def setup_stockfish():
    url = "https://f-droid.org/repo/org.petero.droidfish_99.apk"
    print(f"Downloading DroidFish APK from {url}...")
    
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as res:
        apk_data = res.read()
        
    zf = zipfile.ZipFile(io.BytesIO(apk_data))
    
    mapping = {
        'assets/arm64-v8a/stockfish': 'android_copilot/app/src/main/assets/bin/arm64-v8a/stockfish',
        'assets/armeabi-v7a/stockfish': 'android_copilot/app/src/main/assets/bin/armeabi-v7a/stockfish',
        'assets/x86_64/stockfish': 'android_copilot/app/src/main/assets/bin/x86_64/stockfish',
    }
    
    for src_entry, dest_path in mapping.items():
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        data = zf.read(src_entry)
        with open(dest_path, 'wb') as f:
            f.write(data)
        print(f"Extracted {src_entry} -> {dest_path} (size: {len(data):,} bytes)")

    print("All Stockfish binaries extracted successfully!")
    return zf

def setup_nnue(zf=None):
    """提取 NNUE 评估网络: 优先 DroidFish APK 同源，回退 Stockfish 官方直链"""
    apk_entries = set(zf.namelist()) if zf is not None else set()
    for name, dest_path in NNUE_FILES.items():
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        data = None
        # 源 1: APK 内任意同名 .nnue 条目
        for entry in apk_entries:
            if os.path.basename(entry) == name:
                data = zf.read(entry)
                print(f"Extracted NNUE from APK entry {entry}")
                break
        # 源 2: 官方直链
        if data is None:
            direct_url = NNUE_DIRECT_URL_TMPL.format(name=name)
            print(f"Downloading NNUE from {direct_url} ...")
            req = urllib.request.Request(direct_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=300) as res:
                data = res.read()
        if len(data) < 10 * 1024 * 1024:
            raise RuntimeError(f"NNUE {name} 过小 ({len(data)} bytes)，疑似下载失败")
        with open(dest_path, 'wb') as f:
            f.write(data)
        print(f"NNUE ready: {dest_path} (size: {len(data):,} bytes)")

if __name__ == '__main__':
    zf = None
    try:
        zf = setup_stockfish()
    except Exception as e:
        print(f"APK 源不可用 ({e})，NNUE 将走官方直链")
    setup_nnue(zf)
