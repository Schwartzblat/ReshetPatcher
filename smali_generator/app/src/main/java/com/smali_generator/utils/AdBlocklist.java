package com.smali_generator.utils;

import java.util.Locale;

/**
 * Ad hosts and the canned responses served in their place, shared by the
 * WebView and OkHttp blockers.
 *
 * Ad-tag requests (GAM /gampad/ads) get a valid but empty VAST or VMAP rather
 * than an error: the C13 JS retries a failed VMAP fetch three times, 15 s apart,
 * and IMA treats an empty VAST as "no ads" and goes straight to content.
 *
 * Content hosts (13tv.co.il, *.kaltura.com, reshet.g-mana.live, media.reshet.tv)
 * and imasdk.googleapis.com are never matched here. Players can throw when the
 * IMA loader itself is missing, while an empty ad response is always handled.
 */
public final class AdBlocklist {

    public static final String EMPTY_VAST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"/>";
    public static final String EMPTY_VMAP =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"/>";

    /**
     * VMAP for the C13 JS's own VOD schedule fetch. The JS treats a schedule with
     * no midroll breaks as a failed resolution: it retries, and it holds the source
     * for its full 8 s VOD_STARTUP_VMAP_SOURCE_HOLD_MS. So this one has a single
     * midroll that resolves the schedule at once but sits at 99:59:59 of watch
     * time, where no episode ever gets. The breakId must not contain "pre" (that
     * makes it a preroll). If the break were ever reached, its ad tag is GAM again,
     * which gets an empty VAST, and TheoImaHook drops the ad anyway.
     */
    public static final String NEVER_REACHED_VMAP =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                    + "<vmap:AdBreak timeOffset=\"99:59:59.000\" breakType=\"linear\" breakId=\"patch-never\">"
                    + "<vmap:AdSource id=\"patch\" allowMultipleAds=\"false\" followRedirects=\"true\">"
                    + "<vmap:AdTagURI templateType=\"vast3\"><![CDATA["
                    + "https://pubads.g.doubleclick.net/gampad/ads?iu=/patch/none&output=vast"
                    + "]]></vmap:AdTagURI></vmap:AdSource></vmap:AdBreak></vmap:VMAP>";

    /**
     * Stand-in for gpt.js. Pages call googletag.pubads()/defineSlot() as well as
     * googletag.cmd.push(), and with an empty script those calls throw and can
     * take the rest of the page script down with them. This stub answers the
     * whole API with chainable no-ops and runs the queued commands, so no slot is
     * ever requested.
     */
    public static final String GPT_STUB_JS = "(function(){var w=window,n=function(){};"
            + "var s={};['addService','clearCategoryExclusions','clearTargeting','defineSizeMapping','set',"
            + "'setCategoryExclusion','setClickUrl','setCollapseEmptyDiv','setConfig','setForceSafeFrame',"
            + "'setSafeFrameConfig','setTargeting','updateTargetingFromMap'].forEach(function(k){s[k]=function(){return s}});"
            + "s.getAdUnitPath=s.getSlotElementId=function(){return ''};"
            + "s.getTargeting=s.getTargetingKeys=s.getSizes=function(){return []};"
            + "s.getResponseInformation=function(){return null};"
            + "var p={};['addEventListener','removeEventListener','clear','clearCategoryExclusions',"
            + "'clearTagForChildDirectedTreatment','clearTargeting','collapseEmptyDivs','disableInitialLoad','display',"
            + "'enableAsyncRendering','enableLazyLoad','enableSingleRequest','enableSyncRendering','enableVideoAds',"
            + "'refresh','set','setCategoryExclusion','setCentering','setConfig','setCookieOptions','setForceSafeFrame',"
            + "'setLocation','setPrivacySettings','setPublisherProvidedId','setRequestNonPersonalizedAds',"
            + "'setSafeFrameConfig','setTagForChildDirectedTreatment','setTargeting','setVideoContent','updateCorrelator']"
            + ".forEach(function(k){p[k]=function(){return p}});"
            + "p.getSlots=p.getTargeting=p.getTargetingKeys=function(){return []};"
            + "var g=w.googletag||{},q=g.cmd||[];"
            + "g.apiReady=g.pubadsReady=true;g.pubads=g.companionAds=g.content=function(){return p};"
            + "g.defineSlot=g.defineOutOfPageSlot=function(){return s};"
            + "g.destroySlots=g.disablePublisherConsole=g.display=g.enableServices=g.openConsole=g.setAdIframeTitle=g.setConfig=n;"
            + "g.sizeMapping=function(){var m={addSize:function(){return m},build:function(){return []}};return m};"
            + "g.cmd={push:function(f){try{f.call(w)}catch(e){}return 1}};w.googletag=g;"
            + "for(var i=0;i<q.length;i++){try{q[i].call(w)}catch(e){}}})();";

    // Google ad serving, creatives and conversion tracking. Blocked everywhere.
    private static final String[] GOOGLE_AD_DOMAINS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagservices.com",
            "2mdn.net",
    };

    // Blocked inside WebViews only. The native Taboola SDK talks to these hosts
    // as well, and an SDK that gets an unexpected empty reply over OkHttp may throw.
    private static final String[] WEB_ONLY_AD_DOMAINS = {
            "taboola.com",
            "taboolasyndication.com",
    };

    private AdBlocklist() {
    }

    private static boolean matches(String host, String[] domains) {
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGoogleAdHost(String host) {
        return matches(host, GOOGLE_AD_DOMAINS);
    }

    public static boolean isWebAdHost(String host) {
        return isGoogleAdHost(host) || matches(host, WEB_ONLY_AD_DOMAINS);
    }

    /** A GAM VAST/VMAP ad-tag request, as built by the C13 JS and by IMA. */
    public static boolean isAdTagRequest(String host, String path) {
        if (host == null || path == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return (host.equals("pubads.g.doubleclick.net") || host.equals("securepubads.g.doubleclick.net"))
                && (path.startsWith("/gampad/ads") || path.startsWith("/gampad/live/ads"));
    }

    /** GAM asks for a VMAP playlist with ad_rule=1 or output=vmap / xml_vmap1. */
    public static String emptyAdTagBody(String output, String adRule) {
        boolean vmap = "1".equals(adRule)
                || (output != null && output.toLowerCase(Locale.ROOT).contains("vmap"));
        return vmap ? EMPTY_VMAP : EMPTY_VAST;
    }

    /** Like emptyAdTagBody, but a VMAP answer is NEVER_REACHED_VMAP (for the C13 JS, via OkHttp). */
    public static String appAdTagBody(String output, String adRule) {
        String body = emptyAdTagBody(output, adRule);
        return EMPTY_VMAP.equals(body) ? NEVER_REACHED_VMAP : body;
    }

    public static boolean isGptScript(String host, String path) {
        return host != null && path != null && isGoogleAdHost(host) && path.endsWith("/gpt.js");
    }
}
