package com.estebanruiz.estebantv

import android.net.Uri

/** Filtro de anuncios/popups y detección de URLs de video (video sniffing). */
object AdBlocker {

    private val blockedDomains = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "juicyads.com", "adsterra.com", "trafficjunky.com",
        "taboola.com", "outbrain.com", "adnxs.com", "admaven.com", "ad-maven.com",
        "hilltopads.net", "clickadu.com", "onclickads.net", "mgid.com",
        "revcontent.com", "popunderjs.com", "adcash.com", "richpush.co",
        "monetag.com", "a-ads.com", "adskeeper.com", "yllix.com", "tsyndicate.com",
        "trafficstars.com", "ero-advertising.com", "profitablecpmrate.com",
        "highperformanceformat.com", "effectivegatecpm.com", "realsrv.com", "acint.net"
    )

    /** Buscadores: sus enlaces a otras webs siempre se permiten. */
    fun isSearchHub(host: String): Boolean =
        host.startsWith("google.") || host.contains(".google.") || host.endsWith("bing.com") ||
            host.endsWith("duckduckgo.com") || host.endsWith("search.brave.com") || host.endsWith("yahoo.com")

    // .m3u8 (HLS), .mpd (DASH) y .mp4, seguidos de fin de URL, ?, & o #
    private val streamRegex = Regex("""\.(m3u8|mpd|mp4)(?=$|[?&#])""", RegexOption.IGNORE_CASE)

    fun host(url: String): String = try {
        Uri.parse(url).host?.lowercase().orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun isAdUrl(url: String): Boolean {
        val h = host(url)
        if (h.isEmpty()) return false
        return blockedDomains.any { h == it || h.endsWith(".$it") }
    }

    fun isStreamUrl(url: String): Boolean = try {
        val u = Uri.parse(url)
        val s = (u.path ?: "") + (if (u.query != null) "?" + u.query else "")
        streamRegex.containsMatchIn(s)
    } catch (e: Exception) {
        false
    }

    fun isMpd(url: String) = Regex("""\.mpd(?=$|[?&#])""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    fun isM3u8(url: String) = Regex("""\.m3u8(?=$|[?&#])""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** Dominio "registrable" aproximado: ejemplo.com, ejemplo.co.uk */
    fun registrable(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val slds = setOf("co", "com", "org", "net", "gov", "edu")
        return if (p.last().length == 2 && p[p.size - 2] in slds) p.takeLast(3).joinToString(".")
        else p.takeLast(2).joinToString(".")
    }

    /** window.open devuelve una ventana "cerrada": las webs no pueden abrir pestañas emergentes. */
    const val AD_JS = """
        (function(){
          try{ window.open=function(){return {closed:true,close:function(){},focus:function(){},blur:function(){}};}; }catch(e){}
        })();
    """

    /** Avisa a la app cuando un <video> recibe una fuente http(s) de streaming. */
    const val VIDEO_HOOK_JS = """
        (function(){
          if(window.__etvHook) return; window.__etvHook=true;
          function chk(v){ try{ var s=v.currentSrc||v.src; if(s && /^https?:/i.test(s)) EstebanBridge.onSource(s); }catch(e){} }
          function scan(){
            document.querySelectorAll('video').forEach(function(v){
              if(v.__etv) return; v.__etv=true;
              v.addEventListener('loadstart',function(){chk(v)});
              v.addEventListener('play',function(){chk(v)});
              chk(v);
            });
          }
          scan();
          new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
        })();
    """

    /**
     * Distingue toques reales sobre enlaces visibles de los "clics fantasma" (capas transparentes,
     * scripts que navegan). Informa a la app de cada clic: href y si el enlace parece legítimo.
     */
    const val LINK_GUARD_JS = """
        (function(){
          if(window.__etvLink) return; window.__etvLink=true;
          document.addEventListener('click',function(e){
            try{
              var t=e.target, a=(t&&t.closest)?t.closest('a[href]'):null, ok=false, href='';
              if(a){
                href=a.href;
                var r=a.getBoundingClientRect(), cs=getComputedStyle(a);
                var big=(r.width*r.height)>(innerWidth*innerHeight*0.5);
                var hid=(parseFloat(cs.opacity)<0.1)||cs.visibility==='hidden';
                var txt=((a.innerText||a.textContent||'')+'').trim().length>0||!!a.querySelector('img,svg,picture,video');
                ok=!big&&!hid&&txt&&e.isTrusted;
              }
              EstebanBridge.onLinkClick(href,ok);
            }catch(x){}
          },true);
        })();
    """
}
