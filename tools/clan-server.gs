/**
 * 디아4 보스알람 - 클랜 홍보 서버 (Google Apps Script)
 *
 * 이 파일을 구글 시트의 [확장 프로그램] → [Apps Script]에 붙여넣고 웹 앱으로 배포하면,
 * 앱이 이 주소로 클랜 신청·승인·신고를 주고받습니다. 서버 비용은 들지 않습니다.
 * 배포해서 받은 주소를 season.json의 "clans": {"url": "..."} 에 넣으면 기능이 켜집니다.
 *
 * 시트 탭은 처음 실행될 때 자동으로 만들어집니다.
 *  - 신청 : 클랜이 앱에서 넣은 신청과 그 상태 (관리자 화면에서 승인/거절)
 *  - 신고 : 사용자가 누른 신고
 *  - 설정 : 자리 수, 노출 기간, 유료 모드, 비밀번호
 *  - 기록 : 노출이 끝난 클랜의 이력 (투명성 공개용)
 *
 * 개인정보는 받지 않습니다. 신청에는 클랜의 공개 정보만, 신고에는 클랜 번호만 담깁니다.
 */

var SHEETS = {
  apply: ['시각', '토큰', '클랜명', '한줄소개', '활동시간', '오픈채팅', '디스코드', '배틀태그', '연락처',
          '상태', '시작', '종료', '노출수', '신고수', '메모'],
  report: ['시각', '클랜번호', '사유'],
  config: ['항목', '값'],
  log: ['클랜명', '시작', '종료', '노출수'],
};
var DEFAULTS = [
  ['비밀번호', 'changeme'],
  ['자리수', 3],
  ['노출일수', 30],
  ['유료모드', '아니오'],
  ['가격안내', ''],
  ['사용자수', 0],
];
// 상태 값: 대기 / 노출 / 예약 / 거절 / 종료
var OPEN_LINKS = ['https://open.kakao.com/', 'https://discord.gg/', 'https://discord.com/invite/'];

function sheet_(name) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.appendRow(SHEETS[nameKey_(name)]);
    if (name === '설정') DEFAULTS.forEach(function (r) { sh.appendRow(r); });
  }
  return sh;
}
function nameKey_(name) {
  return { '신청': 'apply', '신고': 'report', '설정': 'config', '기록': 'log' }[name];
}
function rows_(name) {
  var sh = sheet_(name);
  var values = sh.getDataRange().getValues();
  values.shift();
  return values;
}
function config_() {
  var out = {};
  rows_('설정').forEach(function (r) { if (r[0]) out[String(r[0]).trim()] = r[1]; });
  return out;
}
function okLink_(v) {
  v = String(v || '').trim();
  if (!v) return '';
  for (var i = 0; i < OPEN_LINKS.length; i++) if (v.indexOf(OPEN_LINKS[i]) === 0) return v;
  return '';
}
function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
function text_(s) {
  return ContentService.createTextOutput(s).setMimeType(ContentService.MimeType.TEXT);
}

/** 앱이 읽어가는 목록. 개인정보나 연락처는 절대 내보내지 않습니다. */
function doGet(e) {
  var action = (e && e.parameter && e.parameter.a) || 'list';
  if (action === 'mine') return mine_(String(e.parameter.token || '').trim());
  if (action !== 'list') return text_('');
  rotate_();
  var cfg = config_();
  var featured = [], list = [], queue = [];
  rows_('신청').forEach(function (r, i) {
    var state = String(r[9] || '').trim();
    if (state === '거절' || state === '') return;
    var clan = {
      id: String(i + 2),
      name: String(r[2] || ''),
      line: String(r[3] || ''),
      when: String(r[4] || ''),
      chat: okLink_(r[5]),
      discord: okLink_(r[6]),
      tag: String(r[7] || ''),
    };
    if (state === '노출') {
      clan.starts = r[10] ? new Date(r[10]).getTime() : 0;
      clan.ends = r[11] ? new Date(r[11]).getTime() : 0;
      featured.push(clan);
      list.push(clan);
    } else if (state === '예약') {
      queue.push({ name: clan.name, starts: r[10] ? new Date(r[10]).getTime() : 0 });
    } else if (state === '대기') {
      // 승인 전에는 목록에 보이지 않습니다
    } else if (state === '종료') {
      list.push(clan);
    }
  });
  return json_({
    featured: featured,
    list: list,
    queue: queue,
    slots: Number(cfg['자리수'] || 3),
    days: Number(cfg['노출일수'] || 30),
    paid: String(cfg['유료모드'] || '') === '예',
    price: String(cfg['가격안내'] || ''),
    users: Number(cfg['사용자수'] || 0),
    now: Date.now(),
  });
}

/** 기간이 끝난 클랜을 내리고, 빈 자리에 예약 1번을 올립니다. 목록을 읽을 때마다 저절로 돌아갑니다. */
function rotate_() {
  var sh = sheet_('신청');
  var values = sh.getDataRange().getValues();
  var cfg = config_();
  var slots = Number(cfg['자리수'] || 3);
  var days = Number(cfg['노출일수'] || 30);
  var now = new Date();
  var live = 0, changed = false;

  for (var i = 1; i < values.length; i++) {
    if (String(values[i][9]).trim() !== '노출') continue;
    var ends = values[i][11] ? new Date(values[i][11]) : null;
    if (ends && ends.getTime() <= now.getTime()) {
      sh.getRange(i + 1, 10).setValue('종료');
      sheet_('기록').appendRow([values[i][2], values[i][10], values[i][11], values[i][12]]);
      changed = true;
    } else {
      live++;
    }
  }
  for (var j = 1; j < values.length && live < slots; j++) {
    if (String(values[j][9]).trim() !== '예약') continue;
    var end = new Date(now.getTime() + days * 24 * 3600 * 1000);
    sh.getRange(j + 1, 10, 1, 3).setValues([['노출', now, end]]);
    live++;
    changed = true;
  }
  if (changed) SpreadsheetApp.flush();
}

function doPost(e) {
  var p = (e && e.parameter) || {};
  var action = p.a || '';
  if (action === 'apply') return apply_(p);
  if (action === 'report') return report_(p);
  if (action === 'admin') return admin_(p);
  return text_('');
}

/** 앱 안에서 들어온 클랜 신청. 같은 토큰(같은 폰)은 하루 한 번만 받습니다. */
function apply_(p) {
  var data;
  try { data = JSON.parse(p.data || '{}'); } catch (err) { return text_('신청 내용을 읽지 못했어요'); }
  var name = String(data.name || '').trim();
  var chat = okLink_(data.chat), discord = okLink_(data.discord);
  if (name.length < 2) return text_('클랜명을 입력해 주세요');
  if (!chat && !discord && !String(data.tag || '').trim()) {
    return text_('오픈채팅, 디스코드, 배틀태그 중 하나는 넣어 주세요');
  }
  var token = String(p.token || '').trim();
  var sh = sheet_('신청');
  var values = sh.getDataRange().getValues();
  var dayAgo = Date.now() - 24 * 3600 * 1000;
  for (var i = 1; i < values.length; i++) {
    if (String(values[i][1]).trim() !== token) continue;
    var state = String(values[i][9]).trim();
    if (state === '대기' || state === '예약' || state === '노출') return text_('이미 신청하신 클랜이 심사 중이거나 노출 중이에요');
    if (values[i][0] && new Date(values[i][0]).getTime() > dayAgo) return text_('신청은 하루에 한 번만 가능해요');
  }
  for (var k = 1; k < values.length; k++) {
    if (String(values[k][2]).trim() === name && String(values[k][9]).trim() !== '거절') {
      return text_('같은 이름의 클랜이 이미 등록돼 있어요');
    }
  }
  sh.appendRow([new Date(), token, name, String(data.line || '').slice(0, 60), String(data.when || '').slice(0, 30),
    chat, discord, String(data.tag || '').slice(0, 40), String(data.contact || '').slice(0, 60), '대기', '', '', 0, 0, '']);
  notify_('새 클랜 신청: ' + name, '앱 관리자 화면에서 승인하거나 거절할 수 있어요.');
  return text_('신청이 접수됐어요. 확인되면 순서대로 노출돼요.');
}

/** 신청한 폰이 자기 신청의 상태와 거절 사유를 확인합니다 (토큰을 아는 폰만). */
function mine_(token) {
  if (token.length < 8) return json_({});
  var values = sheet_('신청').getDataRange().getValues(), last = null, pos = 0;
  for (var i = 1; i < values.length; i++) if (String(values[i][1]).trim() === token) last = i;
  if (last === null) return json_({});
  var r = values[last], state = String(r[9] || '').trim();
  if (state === '예약') {
    for (var k = 1; k <= last; k++) if (String(values[k][9]).trim() === '예약') pos++;
  }
  return json_({ name: String(r[2] || ''), state: state, reason: state === '거절' ? String(r[14] || '') : '',
    pos: pos, ends: r[11] ? new Date(r[11]).getTime() : 0, time: r[0] ? new Date(r[0]).getTime() : 0 });
}

/** 사용자의 신고. 무료 클랜은 3건이면 저절로 숨고, 노출 중인 클랜은 숨기지 않고 알림만 보냅니다. */
function report_(p) {
  var id = Number(p.id || 0);
  if (!id) return text_('');
  sheet_('신고').appendRow([new Date(), id, String(p.reason || '').slice(0, 200)]);
  var sh = sheet_('신청');
  var row = sh.getRange(id, 1, 1, SHEETS.apply.length).getValues()[0];
  var count = Number(row[13] || 0) + 1;
  sh.getRange(id, 14).setValue(count);
  if (count >= 3 && String(row[9]).trim() !== '노출') sh.getRange(id, 10).setValue('거절');
  notify_('클랜 신고 ' + count + '건: ' + row[2], String(p.reason || ''));
  return text_('신고해 주셔서 고마워요. 확인할게요.');
}

/** 앱 안 관리자 화면. 비밀번호는 시트의 [설정] 탭에서 언제든 바꿀 수 있습니다. */
function admin_(p) {
  var cfg = config_();
  if (String(p.pw || '') !== String(cfg['비밀번호'] || '')) return text_('비밀번호가 맞지 않아요');
  var sh = sheet_('신청');
  var act = String(p['do'] || '');
  var id = Number(p.id || 0);

  if (act === 'list') {
    var out = [];
    rows_('신청').forEach(function (r, i) {
      out.push({
        id: String(i + 2), time: r[0] ? new Date(r[0]).getTime() : 0, name: String(r[2] || ''),
        line: String(r[3] || ''), when: String(r[4] || ''), chat: String(r[5] || ''),
        discord: String(r[6] || ''), tag: String(r[7] || ''), contact: String(r[8] || ''),
        state: String(r[9] || ''), starts: r[10] ? new Date(r[10]).getTime() : 0,
        ends: r[11] ? new Date(r[11]).getTime() : 0, views: Number(r[12] || 0), reports: Number(r[13] || 0),
      });
    });
    return json_({ items: out, slots: Number(cfg['자리수'] || 3), days: Number(cfg['노출일수'] || 30),
      paid: String(cfg['유료모드'] || '') === '예' });
  }
  if (!id) return text_('');

  if (act === 'approve') {
    // 빈 자리가 있으면 바로 노출, 없으면 예약(선착순 대기)
    var live = 0;
    rows_('신청').forEach(function (r) { if (String(r[9]).trim() === '노출') live++; });
    if (live < Number(cfg['자리수'] || 3)) {
      var now = new Date();
      var end = new Date(now.getTime() + Number(cfg['노출일수'] || 30) * 24 * 3600 * 1000);
      sh.getRange(id, 10, 1, 3).setValues([['노출', now, end]]);
      return text_('승인했어요. 바로 노출을 시작해요.');
    }
    sh.getRange(id, 10).setValue('예약');
    return text_('승인했어요. 자리가 나면 순서대로 올라가요.');
  }
  if (act === 'reject') {
    sh.getRange(id, 10).setValue('거절');
    sh.getRange(id, 15).setValue(String(p.v || ''));
    return text_('거절했어요.');
  }
  if (act === 'stop') {
    sh.getRange(id, 10).setValue('종료');
    sh.getRange(id, 12).setValue(new Date());
    return text_('노출을 내렸어요.');
  }
  if (act === 'extend') {
    var days = Number(p.v || 7);
    var cur = sh.getRange(id, 12).getValue();
    var base = cur ? new Date(cur) : new Date();
    sh.getRange(id, 12).setValue(new Date(base.getTime() + days * 24 * 3600 * 1000));
    return text_(days + '일 연장했어요.');
  }
  if (act === 'restore') {
    sh.getRange(id, 10).setValue('예약');
    sh.getRange(id, 14).setValue(0);
    return text_('되살렸어요. 자리가 나면 노출돼요.');
  }
  return text_('');
}

/** 새 신청이나 신고가 오면 시트 주인에게 메일로 알립니다. 실패해도 신청 자체는 접수됩니다. */
function notify_(subject, body) {
  try {
    MailApp.sendEmail(Session.getEffectiveUser().getEmail(), '[디아4알람] ' + subject, body);
  } catch (err) {
    // 메일 할당량을 넘겨도 신청은 그대로 시트에 남습니다
  }
}
