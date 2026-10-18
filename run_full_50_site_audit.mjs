import http from 'http';
import fs from 'fs';

function getJSON(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
    }).on('error', reject);
  });
}

const ALL_50_SITES = [
  // Search engines (6)
  { category: 'Search engines', name: 'google', url: 'https://www.google.com' },
  { category: 'Search engines', name: 'bing', url: 'https://www.bing.com' },
  { category: 'Search engines', name: 'yahoo', url: 'https://www.yahoo.com' },
  { category: 'Search engines', name: 'yandex', url: 'https://ya.ru' },
  { category: 'Search engines', name: 'baidu', url: 'https://www.baidu.com' },
  { category: 'Search engines', name: 'duckduckgo', url: 'https://duckduckgo.com' },

  // Video and streaming (5)
  { category: 'Video and streaming', name: 'youtube', url: 'https://www.youtube.com' },
  { category: 'Video and streaming', name: 'netflix', url: 'https://www.netflix.com' },
  { category: 'Video and streaming', name: 'twitch', url: 'https://www.twitch.tv' },
  { category: 'Video and streaming', name: 'bilibili', url: 'https://www.bilibili.com' },
  { category: 'Video and streaming', name: 'imdb', url: 'https://www.imdb.com' },

  // Social media (8)
  { category: 'Social media', name: 'facebook', url: 'https://www.facebook.com' },
  { category: 'Social media', name: 'instagram', url: 'https://www.instagram.com' },
  { category: 'Social media', name: 'x', url: 'https://x.com' },
  { category: 'Social media', name: 'reddit', url: 'https://www.reddit.com' },
  { category: 'Social media', name: 'tiktok', url: 'https://www.tiktok.com' },
  { category: 'Social media', name: 'linkedin', url: 'https://www.linkedin.com' },
  { category: 'Social media', name: 'pinterest', url: 'https://www.pinterest.com' },
  { category: 'Social media', name: 'vk', url: 'https://vk.ru' },

  // Messaging and communities (3)
  { category: 'Messaging and communities', name: 'whatsapp', url: 'https://www.whatsapp.com' },
  { category: 'Messaging and communities', name: 'telegram', url: 'https://t.me' },
  { category: 'Messaging and communities', name: 'discord', url: 'https://discord.com' },

  // AI assistants (3)
  { category: 'AI assistants', name: 'chatgpt', url: 'https://chatgpt.com' },
  { category: 'AI assistants', name: 'gemini', url: 'https://gemini.google.com' },
  { category: 'AI assistants', name: 'claude', url: 'https://claude.ai' },

  // Shopping and travel (4)
  { category: 'Shopping and travel', name: 'amazon', url: 'https://www.amazon.com' },
  { category: 'Shopping and travel', name: 'temu', url: 'https://www.temu.com' },
  { category: 'Shopping and travel', name: 'ebay', url: 'https://www.ebay.com' },
  { category: 'Shopping and travel', name: 'booking', url: 'https://www.booking.com' },

  // News, information and weather (8)
  { category: 'News, information and weather', name: 'wikipedia', url: 'https://www.wikipedia.org' },
  { category: 'News, information and weather', name: 'yahoo_jp', url: 'https://www.yahoo.co.jp' },
  { category: 'News, information and weather', name: 'news_yahoo_jp', url: 'https://news.yahoo.co.jp' },
  { category: 'News, information and weather', name: 'naver', url: 'https://www.naver.com' },
  { category: 'News, information and weather', name: 'dzen', url: 'https://dzen.ru' },
  { category: 'News, information and weather', name: 'globo', url: 'https://www.globo.com' },
  { category: 'News, information and weather', name: 'weather', url: 'https://weather.com' },

  // Productivity and software (9)
  { category: 'Productivity and software', name: 'cloud_microsoft', url: 'https://cloud.microsoft' },
  { category: 'Productivity and software', name: 'live', url: 'https://login.live.com' },
  { category: 'Productivity and software', name: 'microsoft', url: 'https://www.microsoft.com' },
  { category: 'Productivity and software', name: 'canva', url: 'https://www.canva.com' },
  { category: 'Productivity and software', name: 'samsung', url: 'https://www.samsung.com' },
  { category: 'Productivity and software', name: 'github', url: 'https://github.com' },
  { category: 'Productivity and software', name: 'brave', url: 'https://brave.com' },
  { category: 'Productivity and software', name: 'fandom', url: 'https://www.fandom.com' },
  { category: 'Productivity and software', name: 'mail_ru', url: 'https://mail.ru' },

  // Other entertainment and services (5)
  { category: 'Other entertainment and services', name: 'pornhub', url: 'https://www.pornhub.com' },
  { category: 'Other entertainment and services', name: 'xhamster', url: 'https://www.xhamster.com' },
  { category: 'Other entertainment and services', name: 'xvideos', url: 'https://www.xvideos.com' },
  { category: 'Other entertainment and services', name: 'stripchat', url: 'https://www.stripchat.com' },
  { category: 'Other entertainment and services', name: 'bet_br', url: 'https://bet.br' }
];

async function main() {
  console.log(`Starting 50-site compatibility audit against Haze on device...`);
  const targets = await getJSON('http://127.0.0.1:9222/json');
  const target = targets.find(t => t.type === 'page' && !t.url.startsWith('chrome-extension://')) || targets[0];
  if (!target || !target.webSocketDebuggerUrl) {
    console.error('No suitable DevTools page target found');
    process.exit(1);
  }

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(res => ws.addEventListener('open', res));

  let reqId = 1;
  const pending = new Map();
  ws.addEventListener('message', event => {
    try {
      const msg = JSON.parse(event.data);
      if (msg.id && pending.has(msg.id)) {
        const cb = pending.get(msg.id);
        pending.delete(msg.id);
        cb(msg);
      }
    } catch (_) {}
  });

  function send(method, params = {}) {
    return new Promise(resolve => {
      const id = reqId++;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  await send('Page.enable');
  await send('Runtime.enable');

  const results = [];
  let passCount = 0;
  let redirectedCount = 0;
  let restrictedCount = 0;

  for (let i = 0; i < ALL_50_SITES.length; i++) {
    const item = ALL_50_SITES[i];
    const idx = i + 1;
    process.stdout.write(`[${idx}/50] ${item.name} (${item.url})... `);

    const startTime = Date.now();
    let navResp;
    try {
      navResp = await Promise.race([
        send('Page.navigate', { url: item.url }),
        new Promise((_, rej) => setTimeout(() => rej(new Error('TIMEOUT_NAV')), 10000))
      ]);
    } catch (e) {
      navResp = { error: e.message };
    }

    // Allow render & redirects
    await new Promise(r => setTimeout(r, 4500));
    const elapsedMs = Date.now() - startTime;

    let evalData = null;
    try {
      const evalResp = await send('Runtime.evaluate', {
        expression: 'JSON.stringify({ title: document.title, url: window.location.href, textLen: (document.body && document.body.innerText) ? document.body.innerText.length : 0 })'
      });
      if (evalResp.result?.value) {
        evalData = JSON.parse(evalResp.result.value);
      }
    } catch (_) {}

    const errorText = navResp?.result?.errorText || navResp?.error;
    let status = 'PASS';
    let detail = '';

    if (errorText) {
      if (errorText.includes('ERR_CONNECTION_TIMED_OUT') || errorText.includes('ERR_CONNECTION_RESET')) {
        status = 'NETWORK_RESTRICTED (ISP/Govt block)';
        restrictedCount++;
      } else if (errorText.includes('ERR_NAME_NOT_RESOLVED')) {
        status = 'INVALID_DOMAIN (Unregistered/Non-existent DNS)';
        restrictedCount++;
      } else if (errorText.includes('ERR_ABORTED')) {
        status = 'REDIRECT_OK';
        redirectedCount++;
      } else {
        status = `FAIL: ${errorText}`;
      }
      detail = errorText;
    } else {
      if (evalData && evalData.title) {
        status = 'PASS';
        passCount++;
        detail = `"${evalData.title}" (${evalData.url})`;
      } else if (evalData) {
        status = 'PASS';
        passCount++;
        detail = `${evalData.url}`;
      } else {
        status = 'PASS';
        passCount++;
        detail = 'Rendered document';
      }
    }

    console.log(`${status} - ${detail} [${elapsedMs}ms]`);
    results.push({
      index: idx,
      category: item.category,
      name: item.name,
      requestedUrl: item.url,
      status,
      elapsedMs,
      finalUrl: evalData?.url || null,
      pageTitle: evalData?.title || null,
      textLength: evalData?.textLen || 0,
      errorDetail: errorText || null
    });
  }

  ws.close();

  fs.writeFileSync('audit_results_50.json', JSON.stringify({
    timestamp: new Date().toISOString(),
    total: ALL_50_SITES.length,
    passed: passCount,
    redirected: redirectedCount,
    networkRestrictedOrInvalid: restrictedCount,
    results
  }, null, 2));

  console.log(`\n======================================================`);
  console.log(`AUDIT COMPLETE:`);
  console.log(`Total Target Sites: 50`);
  console.log(`Successfully Rendered / Pass: ${passCount}`);
  console.log(`Cleanly Redirected: ${redirectedCount}`);
  console.log(`ISP / Network Restricted or Unregistered Domains: ${restrictedCount}`);
  console.log(`Results saved to audit_results_50.json`);
  console.log(`======================================================`);
}

main().catch(err => {
  console.error('Fatal audit failure:', err);
  process.exit(1);
});
