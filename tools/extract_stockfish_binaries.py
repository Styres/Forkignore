import urllib.request
import zipfile
import io
import os

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

if __name__ == '__main__':
    setup_stockfish()
