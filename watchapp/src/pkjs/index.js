var Clay = require('pebble-clay');
var clayConfig = require('./config.js');
// Take manual control of the events so we can log + forward exactly the key
// the watch expects.
var clay = new Clay(clayConfig, null, { autoHandleEvents: false });

var TIMELINE_API = 'https://timeline-api.rebble.io/v1/user/pins/';
// Cached per-app timeline token (set in the 'ready' handler). The Rebble timeline
// API needs it in the X-User-Token header; the Core app intercepts these PUT/DELETE
// calls locally and syncs the pins to the watch.
var s_token = null;

// Packed-pin delimiters, mirroring the Android side.
var FS = '\u001f'; // between fields of one pin
var RS = '\u001e'; // between pins
var GS = '\u001d'; // between items of a list field (headings/paragraphs)

function pinKey(id) { return 'pin:' + id; }

// Run cb(token) with a usable timeline token. Prefer the cached one, otherwise
// fetch it on demand — this removes the race where a pin payload arrives before
// the 'ready' handler has resolved the token. The Core app intercepts the PUT
// locally, so even a stub token is worth trying; only a hard fetch error blocks.
function withToken(cb) {
  if (s_token && String(s_token).indexOf('ERR:') !== 0) { cb(s_token); return; }
  try {
    Pebble.getTimelineToken(function(token) {
      s_token = token;
      cb(token);
    }, function(err) {
      console.log('withToken: getTimelineToken failed: ' + JSON.stringify(err));
      cb(null);
    });
  } catch (e) {
    console.log('withToken: throw ' + String(e));
    cb(null);
  }
}

// PUT one pin to the Rebble timeline. genericPin + a generic reminder so the watch
// surfaces it both in the timeline and as a notification near the due time.
// Pin action launch code = (actionType in the top 4 bits) | (28-bit task base code).
// The watch decodes this and relays it to Android, which runs the matching action.
function actionCode(base, type) { return (type * 0x10000000) + (base & 0x0FFFFFFF); }

// Same actions as the in-app menu: Done + reminders. Tapping one launches the
// watchapp with the encoded code; the watch/Android pair performs the action.
function pinActions(base, lang) {
  var L = (lang === 'pt')
    ? ['Concluir', 'Lembrar 1h', 'Lembrar 1 Dia', 'Lembrar 1 Sem.']
    : ['Done', 'Remind 1h', 'Remind 1 day', 'Remind 1 week'];
  var out = [];
  for (var t = 0; t < 4; t++) {
    out.push({ title: L[t], type: 'openWatchApp', launchCode: actionCode(base, t) });
  }
  return out;
}

function putPin(id, iso, title, subtitle, code, headings, paragraphs, lang) {
  // Defensive: the caller guards on the token, but putPin sends s_token (the global)
  // in the header — never let a null/ERR token go out as the literal string "null".
  if (!s_token || String(s_token).indexOf('ERR:') === 0) {
    console.log('putPin: no usable timeline token, skipping ' + id);
    return;
  }
  var layout = {
    type: 'genericPin',
    title: title,
    tinyIcon: 'system://images/OBSIDIAN_TIMELINE_ICON',
    backgroundColor: '#5500AA',
    primaryColor: '#FFFFFF',
    secondaryColor: '#FFFFFF'
  };
  if (subtitle) { layout.subtitle = subtitle; }
  // headings/paragraphs render as "small label above value" sections in the detail
  // view (the only structured-content mechanism the Core app actually emits).
  if (headings.length && paragraphs.length) {
    layout.headings = headings;
    layout.paragraphs = paragraphs;
  }

  // One-line summary for the reminder notification (genericReminder shows a body,
  // not headings/paragraphs): "value · value · ...".
  var summary = paragraphs.length ? paragraphs.join(' \u00b7 ') : title;

  // The Core firmware appears not to fire a reminder whose time equals the pin time,
  // so place the pin a few minutes AFTER the reminder: the reminder (the actual alert)
  // fires at `iso`, while the pin's own event time stays in the future at that moment.
  var pinTime = new Date(Date.parse(iso) + 10 * 60 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');

  var pin = {
    id: id,
    time: pinTime,
    layout: layout,
    reminders: [{
      time: iso,
      layout: {
        type: 'genericReminder',
        title: title,
        tinyIcon: 'system://images/OBSIDIAN_TIMELINE_ICON',
        backgroundColor: '#5500AA',
        primaryColor: '#FFFFFF',
        secondaryColor: '#FFFFFF',
        subtitle: subtitle || undefined,
        body: summary
      }
    }],
    actions: pinActions(code || 0, lang)
  };
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', TIMELINE_API + id);
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.setRequestHeader('X-User-Token', s_token);
  xhr.onload = function() {
    console.log('pin PUT ' + id + ' -> ' + xhr.status);
    if (xhr.status >= 200 && xhr.status < 300) {
      try { localStorage.setItem(pinKey(id), '1'); } catch (e) {}
    }
  };
  xhr.onerror = function() { console.log('pin PUT ' + id + ' error'); };
  xhr.send(JSON.stringify(pin));
}

function deletePin(id) {
  // Same token guard as putPin -- never send "null" as the header value.
  if (!s_token || String(s_token).indexOf('ERR:') === 0) {
    console.log('pin DELETE ' + id + ': no usable timeline token, skipping');
    return;
  }
  var xhr = new XMLHttpRequest();
  xhr.open('DELETE', TIMELINE_API + id);
  xhr.setRequestHeader('X-User-Token', s_token);
  xhr.onload = function() {
    console.log('pin DELETE ' + id + ' -> ' + xhr.status);
    // Only forget the pin once the server actually removed it (404 = already
    // gone). Clearing the marker on a 5xx would orphan the pin on the timeline
    // forever -- nothing would ever retry the delete.
    if ((xhr.status >= 200 && xhr.status < 300) || xhr.status === 404) {
      try { localStorage.removeItem(pinKey(id)); } catch (e) {}
    }
  };
  xhr.onerror = function() { console.log('pin DELETE ' + id + ' error'); };
  xhr.send();
}

// Receive the packed pin payload relayed by the watch and reconcile it against the
// set of pins we last pushed (tracked in localStorage): PUT current, DELETE stale.
Pebble.addEventListener('appmessage', function(e) {
  var keys = require('message_keys');
  var p = e.payload || {};
  console.log('appmessage payload: ' + JSON.stringify(p));
  // e.payload may be keyed by message-key NAME or by numeric key depending on the
  // runtime; accept both so the relay from the watch is matched either way.
  var action = (p.KEY_ACTION !== undefined) ? p.KEY_ACTION : p[keys.KEY_ACTION];
  if (action !== 'PINJS') { return; }

  var packed = (p.KEY_TASK_TEXT !== undefined) ? p.KEY_TASK_TEXT : p[keys.KEY_TASK_TEXT];
  packed = packed || '';
  console.log('PINJS received, payload len=' + packed.length);

  withToken(function(token) {
    if (!token) { console.log('PINJS: no timeline token, skipping'); return; }

    var wanted = {};
    if (packed) {
      var pins = packed.split(RS);
      for (var i = 0; i < pins.length; i++) {
        if (!pins[i]) { continue; }
        var f = pins[i].split(FS);
        var id = f[0], iso = f[1], title = f[2] || '';
        var subtitle = f[3] || '';
        var code = parseInt(f[4], 10) || 0;
        var headings = f[5] ? f[5].split(GS) : [];
        var paragraphs = f[6] ? f[6].split(GS) : [];
        var lang = f[7] || 'en';
        if (!id || !iso) { continue; }
        wanted[id] = true;
        putPin(id, iso, title, subtitle, code, headings, paragraphs, lang);
      }
    }

    // Delete any previously-pushed pin no longer present in the current set.
    try {
      for (var j = localStorage.length - 1; j >= 0; j--) {
        var k = localStorage.key(j);
        if (k && k.indexOf('pin:') === 0) {
          var existing = k.substring(4);
          if (!wanted[existing]) { deletePin(existing); }
        }
      }
    } catch (e2) { console.log('PINJS prune error: ' + e2); }
  });
});

// On launch, fetch this user's per-app timeline token and hand it to the watch.
// The watch echoes it back out so the Android companion can cache it and push
// timeline pins for dated tasks (the dates live on the Android side).
Pebble.addEventListener('ready', function() {
  var keys = require('message_keys');
  function relay(value) {
    var out = {};
    out[keys.TL_TOKEN] = value;
    Pebble.sendAppMessage(out, function() {
      console.log('TL_TOKEN relayed: ' + value);
    }, function(err) {
      console.log('TL_TOKEN send failed: ' + JSON.stringify(err));
    });
  }
  try {
    Pebble.getTimelineToken(function(token) {
      console.log('timeline token: ' + token);
      s_token = token;
      relay(token);
    }, function(err) {
      // Surface the failure reason through the watch -> Android log so it is
      // diagnosable without a JS console.
      relay('ERR:' + (typeof err === 'string' ? err : JSON.stringify(err)).substring(0, 40));
    });
  } catch (e) {
    relay('ERR:throw ' + String(e).substring(0, 32));
  }
});

Pebble.addEventListener('showConfiguration', function() {
  Pebble.openURL(clay.generateUrl());
});

Pebble.addEventListener('webviewclosed', function(e) {
  if (!e || !e.response) {
    return;
  }

  // Clay returns the settings keyed by numeric messageKey. The colour comes in
  // as a 24-bit 0xRRGGBB integer; the watch converts it to a GColor itself.
  var dict = clay.getSettings(e.response);
  var keys = require('message_keys');
  var out = {};

  // Colour comes in as a 24-bit 0xRRGGBB integer; the watch converts to GColor.
  var raw = dict[keys.BG_COLOR];
  if (typeof raw !== 'undefined') {
    out[keys.BG_COLOR] = raw & 0xFFFFFF;
  }

  // Voice-note row toggle (boolean -> 0/1 for the watch's int32).
  var voice = dict[keys.VOICE_ON];
  if (typeof voice !== 'undefined') {
    out[keys.VOICE_ON] = voice ? 1 : 0;
  }

  if (Object.keys(out).length === 0) {
    console.log('webviewclosed: no known settings to send');
    return;
  }

  console.log('Sending settings: ' + JSON.stringify(out));
  Pebble.sendAppMessage(out, function() {
    console.log('settings sent OK');
  }, function(err) {
    console.log('settings send failed: ' + JSON.stringify(err));
  });
});
