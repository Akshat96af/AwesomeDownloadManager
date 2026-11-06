// Runs in YouTube's own page context (manifest "world": "MAIN") because the player's data is not visible
// to content scripts. It only answers content.js with the current video's format list; it changes nothing.
(() => {
  function currentVideoId() {
    const params = new URLSearchParams(location.search);
    return params.get('v') || /^\/shorts\/([\w-]{11})/.exec(location.pathname)?.[1] || null;
  }

  function playerResponse(videoId) {
    let response = null;
    // the live player object stays current after YouTube's in-page navigation; the initial data does not
    try { response = document.getElementById('movie_player')?.getPlayerResponse?.() || null; } catch {}
    if (response?.videoDetails?.videoId !== videoId) response = window.ytInitialPlayerResponse || null;
    return response?.videoDetails?.videoId === videoId ? response : null;
  }

  function describe(response) {
    const streaming = response.streamingData || {};
    return {
      videoId: response.videoDetails.videoId,
      title: response.videoDetails.title || 'YouTube video',
      lengthSeconds: Number(response.videoDetails.lengthSeconds) || 0,
      isLive: !!response.videoDetails.isLive,
      abrUrl: streaming.serverAbrStreamingUrl || null,
      ustreamer: response.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig || null,
      formats: (streaming.adaptiveFormats || []).map(f => ({
        itag: f.itag,
        mimeType: f.mimeType || '',
        qualityLabel: f.qualityLabel || '',
        fps: f.fps || 0,
        bitrate: f.bitrate || 0,
        contentLength: Number(f.contentLength) || 0,
        lastModified: f.lastModified || '0',
        xtags: f.xtags || null,
        isDrc: !!f.isDrc,
        // dubbed videos list one audio stream per language; keep the original
        audioDefault: f.audioTrack ? !!f.audioTrack.audioIsDefault : true,
        hasDrm: !!(f.drmFamilies && f.drmFamilies.length),
      })),
    };
  }

  addEventListener('message', event => {
    if (event.source !== window || event.data?.admYouTube !== 'request') return;
    const videoId = currentVideoId();
    const response = videoId && playerResponse(videoId);
    window.postMessage({admYouTube: 'player', nonce: event.data.nonce, data: response ? describe(response) : null}, location.origin);
  });
})();
