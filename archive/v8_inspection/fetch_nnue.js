// 下载 NNUE 网络：优先 DroidFish APK 同源提取；F-Droid 不可用时回退官方直链
// 用法: node fetch_nnue.js
'use strict';
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const APK_URL = 'https://f-droid.org/repo/org.petero.droidfish_99.apk';
const APK_LOCAL = path.join(__dirname, 'droidfish.apk');
const NNUE_OUT_DIR = path.join(__dirname, '..', 'android_copilot', 'app', 'src', 'main', 'assets', 'nnue');
// 官方直链（文件名由引擎二进制的报错信息给出，与编译期内置默认网络严格一致）
const NNUE_DIRECT = [
    { name: 'nn-5af11540bbfe.nnue', url: 'https://tests.stockfishchess.org/api/nn/nn-5af11540bbfe.nnue' }
];

function download(url, dest, redirects = 0) {
    return new Promise((resolve, reject) => {
        if (redirects > 5) return reject(new Error('too many redirects'));
        const req = https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, (res) => {
            if ([301, 302, 303, 307, 308].includes(res.statusCode)) {
                res.resume();
                return resolve(download(new URL(res.headers.location, url).href, dest, redirects + 1));
            }
            if (res.statusCode !== 200) {
                res.resume();
                return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
            }
            const total = parseInt(res.headers['content-length'] || '0', 10);
            let got = 0;
            const ws = fs.createWriteStream(dest);
            res.on('data', (chunk) => {
                got += chunk.length;
                if (total > 0 && Math.floor(got / total * 20) !== Math.floor((got - chunk.length) / total * 20)) {
                    process.stdout.write(`\r  下载进度: ${(got / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)} MB`);
                }
            });
            res.pipe(ws);
            ws.on('finish', () => { ws.close(); console.log('\n  下载完成: ' + dest); resolve(); });
            ws.on('error', reject);
        });
        req.on('error', reject);
    });
}

// 极简 Zip 解析：EOCD -> 中心目录 -> 本地文件头
function readZipEntries(buf) {
    // 从尾部反向搜索 EOCD 签名 0x06054b50
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65536); i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd === -1) throw new Error('EOCD not found');
    const count = buf.readUInt16LE(eocd + 10);
    const cdOffset = buf.readUInt32LE(eocd + 16);
    const entries = [];
    let p = cdOffset;
    for (let i = 0; i < count; i++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central dir entry at ' + p);
        const method = buf.readUInt16LE(p + 10);
        const compSize = buf.readUInt32LE(p + 20);
        const uncompSize = buf.readUInt32LE(p + 24);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const localOff = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
        entries.push({ name, method, compSize, uncompSize, localOff });
        p += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
}

function extractEntry(buf, entry) {
    const p = entry.localOff;
    if (buf.readUInt32LE(p) !== 0x04034b50) throw new Error('bad local header for ' + entry.name);
    const nameLen = buf.readUInt16LE(p + 26);
    const extraLen = buf.readUInt16LE(p + 28);
    const dataStart = p + 30 + nameLen + extraLen;
    const raw = buf.subarray(dataStart, dataStart + entry.compSize);
    if (entry.method === 0) return Buffer.from(raw);
    if (entry.method === 8) return zlib.inflateRawSync(raw);
    throw new Error('unsupported compression method ' + entry.method + ' for ' + entry.name);
}

async function tryFromApk() {
    if (!fs.existsSync(APK_LOCAL)) {
        console.log('从 ' + APK_URL + ' 下载 DroidFish APK ...');
        await download(APK_URL, APK_LOCAL);
    } else {
        console.log('复用已下载 APK: ' + APK_LOCAL);
    }
    const buf = fs.readFileSync(APK_LOCAL);
    const entries = readZipEntries(buf);
    console.log('APK 条目总数: ' + entries.length);
    const nnueEntries = entries.filter(e => e.name.toLowerCase().endsWith('.nnue'));
    if (nnueEntries.length === 0) {
        console.log('!! APK 内未找到 .nnue 文件，assets 清单:');
        entries.filter(e => e.name.startsWith('assets/')).forEach(e => console.log('  ' + e.name));
        return false;
    }
    fs.mkdirSync(NNUE_OUT_DIR, { recursive: true });
    for (const e of nnueEntries) {
        const outName = path.basename(e.name);
        const outPath = path.join(NNUE_OUT_DIR, outName);
        console.log('提取 ' + e.name + ' (' + (e.uncompSize / 1048576).toFixed(1) + ' MB) -> ' + outPath);
        const data = extractEntry(buf, e);
        if (data.length !== e.uncompSize) throw new Error('size mismatch for ' + e.name);
        fs.writeFileSync(outPath, data);
    }
    return true;
}

async function fromDirect() {
    fs.mkdirSync(NNUE_OUT_DIR, { recursive: true });
    for (const item of NNUE_DIRECT) {
        const outPath = path.join(NNUE_OUT_DIR, item.name);
        console.log('从官方直链下载 ' + item.url + ' ...');
        await download(item.url, outPath);
        const data = fs.readFileSync(outPath);
        const sha = crypto.createHash('sha256').update(data).digest('hex');
        console.log('  大小: ' + (data.length / 1048576).toFixed(1) + ' MB | SHA-256: ' + sha);
        // 防下载到 HTML/JSON 错误页：二进制网络文件不应以文本标记开头；NNUE 正常头 4 字节为版本号 0x7AF32F84
        const head4 = data.readUInt32LE(0);
        const looksLikeText = ['<!DO', '<htm', '{', '<'].some(s => data.subarray(0, s.length).toString('latin1').startsWith(s));
        if (looksLikeText || data.length < 10 * 1024 * 1024) {
            throw new Error('下载内容疑似错误页或过小 (head=0x' + head4.toString(16) + ')，拒绝写入');
        }
        console.log('  头部魔数: 0x' + head4.toString(16).toUpperCase() + (head4 === 0x7AF32F84 ? ' (符合 NNUE 版本号)' : ' (非 NNUE 标准版本号，请人工复核)'));
    }
}

(async () => {
    let ok = false;
    try {
        ok = await tryFromApk();
    } catch (e) {
        console.log('APK 源失败: ' + e.message + '，回退官方直链...');
    }
    if (!ok) await fromDirect();
    console.log('NNUE 就绪于: ' + NNUE_OUT_DIR);
})().catch((err) => { console.error('\n失败: ' + err.message); process.exit(1); });
