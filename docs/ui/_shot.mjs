import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const DIR = 'D:\\E\\WB_workspace\\WZY0APP\\docs\\ui\\';
const PORT = 9411;
const W = 1720;

const profile = path.join(os.tmpdir(), 'rm-shot-' + Date.now());
const child = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
  '--no-default-browser-check', '--force-color-profile=srgb',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--window-size=1720,1200', 'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getPageTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await r.json();
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page;
    } catch {}
    await sleep(250);
  }
  throw new Error('CDP not ready');
}

const page = await getPageTarget();
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let id = 0;
const pending = new Map();
ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { res, rej } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
  }
};
function send(method, params = {}) {
  const mid = ++id;
  ws.send(JSON.stringify({ id: mid, method, params }));
  return new Promise((res, rej) => pending.set(mid, { res, rej }));
}

await send('Page.enable');

async function render(file, outBase, rangeExpr) {
  await send('Page.navigate', { url: 'file:///' + file });
  await sleep(2400);

  const h = await send('Runtime.evaluate', {
    expression: 'Math.ceil(Math.max(document.body.scrollHeight, document.documentElement.scrollHeight))',
    returnByValue: true,
  });
  const H = Math.max(1200, h.result.value);

  const ov = await send('Runtime.evaluate', {
    expression: `JSON.stringify([...document.querySelectorAll('.screen')].map((s,i)=>{
      const b=s.querySelector('.body');
      if(!b) return [i,'-','-'];
      let sum=0; [...b.children].forEach(e=>{ const r=e.getBoundingClientRect(); const st=getComputedStyle(e); sum += r.height + parseFloat(st.marginTop) + parseFloat(st.marginBottom); });
      return [i, b.clientHeight, Math.round(sum)];
    }))`,
    returnByValue: true,
  });
  console.log(file.split('\\').pop(), '| height', H, '| overflow', ov.result.value);

  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2, mobile: false });
  await sleep(800);

  const rr = await send('Runtime.evaluate', { expression: rangeExpr, returnByValue: true });
  const ranges = JSON.parse(rr.result.value).map(([a, b]) => [Math.round(Number(a)), Math.round(Number(b))]);
  console.log('  ranges', JSON.stringify(ranges));

  for (let i = 0; i < ranges.length; i++) {
    const [y, height] = ranges[i];
    const sh = await send('Page.captureScreenshot', {
      format: 'png', captureBeyondViewport: true, fromSurface: true,
      clip: { x: 0, y, width: W, height, scale: 1 },
    });
    const out = i === 0 ? outBase + '.png' : outBase + '-0' + (i + 1) + '.png';
    fs.writeFileSync(out, Buffer.from(sh.data, 'base64'));
    console.log('  saved', out.split('\\').pop(), fs.statSync(out).size, 'bytes', `${W}x${height}`);
  }
}

await render(DIR + 'replymate-ui.html', DIR + 'replymate-ui', `(() => {
  const s=[...document.querySelectorAll('.sec')].map(e=>e.getBoundingClientRect());
  const b1=Math.ceil(s[2].bottom+26), t2=Math.floor(s[3].top-48), b2=Math.ceil(s[3].bottom+44);
  return JSON.stringify([[0,b1],[t2,b2-t2]]);
})()`);

await render(DIR + 'replymate-ui-v2.html', DIR + 'replymate-ui-v2', `(() => {
  const s=[...document.querySelectorAll('.sec')].map(e=>e.getBoundingClientRect());
  const b1=Math.ceil(s[1].bottom+30), t2=Math.floor(s[2].top-46), b2=Math.ceil(s[2].bottom+44);
  return JSON.stringify([[0,b1],[t2,b2-t2]]);
})()`);

ws.close();
child.kill();
await sleep(300);
try { fs.rmSync(profile, { recursive: true, force: true }); } catch {}
process.exit(0);
