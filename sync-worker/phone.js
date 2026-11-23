// The phone page: search for a film or show and add it to My List on your Sanj TVs.
// Saved to the phone's home screen. Plain JS (no template literals) so it can live in this string.
export const PHONE_HTML = `<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Sanj TV">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="theme-color" content="#0A101E">
<link rel="manifest" href="/phone/manifest.json">
<title>Sanj TV</title>
<style>
  :root{--bg:#0A101E;--card:#141C2E;--line:#243049;--text:#EEF2F7;--dim:#93A0B4;--accent:#FF6F61;--ok:#4FD18B}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
       padding:env(safe-area-inset-top) 0 env(safe-area-inset-bottom)}
  header{position:sticky;top:0;z-index:2;background:var(--bg);padding:16px 18px 10px;border-bottom:1px solid var(--line)}
  h1{margin:0 0 10px;font-size:22px;letter-spacing:.01em}
  h1 b{color:var(--accent)}
  input{width:100%;font:inherit;font-size:17px;color:var(--text);background:var(--card);border:1px solid var(--line);
        border-radius:12px;padding:12px 14px;outline:none}
  input:focus{border-color:var(--accent)}
  main{padding:8px 14px 40px}
  .msg{color:var(--dim);text-align:center;padding:28px 10px}
  .row{display:flex;gap:12px;background:var(--card);border-radius:14px;padding:10px;margin:10px 0}
  .row img,.row .noimg{width:72px;height:108px;border-radius:8px;object-fit:cover;background:#1E283C;flex:none}
  .info{flex:1;min-width:0;display:flex;flex-direction:column}
  .t{font-weight:700;font-size:16px}
  .m{color:var(--dim);font-size:13px;margin:2px 0 6px}
  .o{color:#C9D2DE;font-size:13px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  button{font:inherit;font-weight:700;border:0;border-radius:10px;padding:9px 14px;cursor:pointer}
  .add{margin-top:auto;align-self:flex-start;background:var(--accent);color:#fff}
  .add[disabled]{background:#2B3A55;color:var(--ok)}
  h2{font-size:15px;color:var(--dim);font-weight:600;margin:22px 4px 4px}
  .pair{max-width:420px;margin:40px auto;text-align:center;padding:0 10px}
  .pair input{text-align:center;font-size:28px;letter-spacing:.3em;margin:14px 0}
  .pair button{width:100%;background:var(--accent);color:#fff;padding:13px}
  .err{color:#FF9A8F;margin-top:10px;min-height:1.4em}
  .small{color:var(--dim);font-size:13px}
  .link{background:none;color:var(--dim);font-weight:400;text-decoration:underline;padding:4px}
</style>
</head><body>
<div id="app"></div>
<script>
var app = document.getElementById("app");
var token = null;
try { token = localStorage.getItem("sanjtv_token"); } catch (e) {}
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return {"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;"}[c]; }); }
function api(path, body) {
  var opt = body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {};
  return fetch("/v1/phone/" + path, opt).then(function (r) { return r.json().then(function (d) {
    if (!r.ok) throw new Error(d.error || "Something went wrong");
    return d;
  }); });
}

function showPair(err) {
  app.innerHTML = '<div class="pair"><h1>Sanj <b>TV</b></h1>' +
    '<p>On your TV, open Sanj TV and go to <b>Settings &rarr; Phone</b>, then press <b>Link a phone</b>. Type the 6-digit code it shows.</p>' +
    '<input id="code" inputmode="numeric" maxlength="6" placeholder="123456" autocomplete="one-time-code">' +
    '<button id="go">Link this phone</button><div class="err" id="err">' + esc(err || "") + '</div></div>';
  var code = document.getElementById("code");
  code.focus();
  document.getElementById("go").onclick = function () {
    api("pair", { code: code.value }).then(function (d) {
      token = d.token;
      try { localStorage.setItem("sanjtv_token", token); } catch (e) {}
      showMain();
    }).catch(function (e) { document.getElementById("err").textContent = e.message; });
  };
}

var added = {};
function showMain() {
  app.innerHTML = '<header><h1>Sanj <b>TV</b></h1><input id="q" type="search" placeholder="Search movies and TV shows" autocomplete="off"></header>' +
    '<main><div id="res"><p class="msg">Find something you\\'d like to watch, then tap <b>Add to My List</b>. It\\'ll be on your TVs next time Sanj TV opens.</p></div>' +
    '<div id="wait"></div><p class="small" style="text-align:center;margin-top:30px"><button class="link" id="out">Unlink this phone</button></p></main>';
  var q = document.getElementById("q"), timer = null;
  q.oninput = function () { clearTimeout(timer); timer = setTimeout(function () { search(q.value); }, 350); };
  document.getElementById("out").onclick = function () {
    if (!confirm("Unlink this phone from Sanj TV?")) return;
    try { localStorage.removeItem("sanjtv_token"); } catch (e) {}
    token = null; showPair();
  };
  loadWaiting();
}

var lastQ = "";
function search(text) {
  var res = document.getElementById("res");
  lastQ = text.trim();
  if (lastQ.length < 2) { res.innerHTML = ""; return; }
  var mine = lastQ;
  res.innerHTML = '<p class="msg">Searching&hellip;</p>';
  fetch("/v1/phone/search?token=" + token + "&q=" + encodeURIComponent(lastQ)).then(function (r) { return r.json().then(function (d) {
    if (!r.ok) throw new Error(d.error || "Search failed");
    return d;
  }); }).then(function (d) {
    if (mine !== lastQ) return;
    if (!d.results.length) { res.innerHTML = '<p class="msg">Nothing found for &ldquo;' + esc(lastQ) + '&rdquo;.</p>'; return; }
    res.innerHTML = "";
    d.results.forEach(function (x) { res.appendChild(card(x)); });
  }).catch(function (e) {
    if (/isn't linked/.test(e.message)) { try { localStorage.removeItem("sanjtv_token"); } catch (x) {} token = null; showPair(e.message); return; }
    res.innerHTML = '<p class="msg">' + esc(e.message) + '</p>';
  });
}

function card(x) {
  var el = document.createElement("div");
  el.className = "row";
  var meta = [x.year, x.series ? "TV show" : "Movie", x.rating ? "\\u2605 " + x.rating : ""].filter(Boolean).join("  \\u00b7  ");
  var done = added[x.tmdb];
  el.innerHTML = (x.poster ? '<img loading="lazy" src="' + esc(x.poster) + '" alt="">' : '<div class="noimg"></div>') +
    '<div class="info"><div class="t">' + esc(x.title) + '</div><div class="m">' + esc(meta) + '</div>' +
    '<div class="o">' + esc(x.overview) + '</div>' +
    '<button class="add"' + (done ? " disabled" : "") + '>' + (done ? "\\u2713 Added" : "\\uFF0B Add to My List") + '</button></div>';
  var b = el.querySelector("button");
  b.onclick = function () {
    b.disabled = true; b.textContent = "Adding\\u2026";
    api("add", { token: token, item: x }).then(function () {
      added[x.tmdb] = true; b.textContent = "\\u2713 Added"; loadWaiting();
    }).catch(function (e) { b.disabled = false; b.textContent = "\\uFF0B Add to My List"; alert(e.message); });
  };
  return el;
}

function loadWaiting() {
  fetch("/v1/phone/list?token=" + token).then(function (r) { return r.json(); }).then(function (d) {
    var w = document.getElementById("wait");
    if (!w || !d.items) return;
    if (!d.items.length) { w.innerHTML = ""; return; }
    w.innerHTML = '<h2>On its way to your TV</h2><p class="small" style="margin:0 4px">These go into My List the next time Sanj TV opens.</p>' +
      d.items.map(function (i) { return '<div class="row" style="padding:8px">' +
        (i.poster ? '<img src="' + esc(i.poster) + '" alt="" style="width:44px;height:66px">' : '') +
        '<div class="info" style="justify-content:center"><div class="t">' + esc(i.title) + '</div><div class="m">' +
        esc([i.year, i.series ? "TV show" : "Movie"].filter(Boolean).join("  \\u00b7  ")) + '</div></div></div>'; }).join("");
  }).catch(function () {});
}

// Scanned the QR code on the TV: link straight away.
var qcode = (location.search.match(/code=(\\d{6})/) || [])[1];
if (qcode) {
  try { history.replaceState(null, "", "/phone"); } catch (e) {}
  api("pair", { code: qcode }).then(function (d) {
    token = d.token;
    try { localStorage.setItem("sanjtv_token", token); } catch (e) {}
    showMain();
  }).catch(function (e) { showPair(e.message); });
} else if (token) showMain(); else showPair();
</script>
</body></html>`;
