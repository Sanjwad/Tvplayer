// Serves the ChinookTV site. Every other host name (www, chinooktv.ca) is sent to chinooktv.com.
const HOME = "chinooktv.com";
const APK = "https://github.com/Sanjwad/Tvplayer/releases/download/latest/tvplayer.apk";
export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.hostname !== HOME && !url.hostname.endsWith(".workers.dev")) {
      url.hostname = HOME; url.protocol = "https:"; url.port = "";
      return Response.redirect(url.toString(), 301);
    }
    if (url.pathname === "/get" || url.pathname === "/get/" || url.pathname === "/apk") {
      return Response.redirect(APK, 302);
    }
    return env.ASSETS.fetch(req);
  },
};
