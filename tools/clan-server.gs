/**
 * 디아4 보스알람 - 클랜 홍보 서버 (Google Apps Script)
 *
 * 이 파일을 구글 시트의 [확장 프로그램] → [Apps Script]에 붙여넣고 웹 앱으로 배포하면,
 * 앱이 이 주소로 클랜 신청·승인·신고를 주고받습니다. 서버 비용은 들지 않습니다.
 * 배포해서 받은 주소를 season.json의 "clans": {"url": "..."} 에 넣으면 기능이 켜집니다.
 *
 * 3.1부터는 완전 무료 + 승인 없음입니다. 앱에서 등록하면 바로 노출되고(자리가 차면 대기 순서대로 자동 시작),
 * 15일이 지나면 저절로 내려갑니다. 사람 손이 필요 없도록 아래 자동 방어가 들어 있어요.
 *  - 글자 수 제한, 링크·금칙어 검사, 같은 폰/같은 채널 중복 막기, 하루 신청 수 한도
 *  - 서로 다른 폰 3대가 신고하면 자동으로 숨김 (관리자 화면에서 되살릴 수 있어요)
 *
 * 시트 탭은 처음 실행될 때 자동으로 만들어집니다.
 *  - 신청 : 클랜이 앱에서 넣은 등록과 그 상태
 *  - 신고 : 사용자가 누른 신고
 *  - 설정 : 자리 수, 노출 기간, 유료 모드, 비밀번호
 *  - 기록 : 노출이 끝난 클랜의 이력 (투명성 공개용)
 *
 * 개인정보는 받지 않습니다. 신청에는 클랜의 공개 정보만, 신고에는 클랜 번호만 담깁니다.
 */

var SHEETS = {
  apply: ['시각', '토큰', '클랜명', '한줄소개', '활동시간', '오픈채팅', '디스코드', '배틀태그', '연락처',
          '상태', '시작', '종료', '노출수', '신고수', '메모'],
  report: ['시각', '클랜번호', '사유', '신고폰'],
  config: ['항목', '값'],
  log: ['클랜명', '시작', '종료', '노출수'],
};
var DEFAULTS = [
  ['비밀번호', 'changeme'],
  ['자리수', 3],
  ['노출일수', 15],
  ['유료모드', '아니오'],
  ['가격안내', ''],
  ['사용자수', 0],
  ['하루신청한도', 10],
];
// 3.1: 노출 기간은 코드에서 15일로 고정하고, 유료 모드는 꺼 둡니다 (시트의 해당 칸 값은 무시해요)
var FREE_DAYS = 15;
var REPORT_LIMIT = 3;
// 상태 값: 노출 / 예약(자리 기다리는 중) / 거절(숨김) / 종료. (대기는 예전 값이라 예약처럼 다뤄요)
// 아래 낱말이 들어 있으면 등록되지 않아요. 필요하면 자유롭게 늘리세요.
var BAD_WORDS = ['시발', '씨발', '병신', '좆', '지랄', '개새', '섹스', '야동', '토토', '카지노', '바카라', '도박',
  '현금거래', '계정판매', '계정거래', '아이템거래', '아이템판매', '대리육성', '대리랭', '먹튀', '한남', '김치녀'];
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
function plain_(v, max) {
  return String(v || '').replace(/[\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}
function hasLink_(v) {
  return /(https?:|www\.|\.com|\.kr|\.net|\.gg|\.me|\.io|open\.kakao|discord|t\.me|@)/i.test(v);
}
function hasBad_(v) {
  var low = String(v).toLowerCase().replace(/[\s.\-_*]/g, '');
  for (var i = 0; i < BAD_WORDS.length; i++) if (low.indexOf(BAD_WORDS[i]) >= 0) return true;
  return false;
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
    } else if (state === '예약' || state === '대기') {
      queue.push({ name: clan.name, starts: 0 });
    } else if (state === '종료') {
      list.push(clan);
    }
  });
  return json_({
    featured: featured,
    list: list,
    queue: queue,
    slots: Number(cfg['자리수'] || 3),
    days: FREE_DAYS,
    paid: false,
    price: '',
    users: Number(cfg['사용자수'] || 0),
    now: Date.now(),
  });
}

/** 기간이 끝난 클랜을 내리고, 빈 자리에 기다리는 클랜을 등록 순서대로 올립니다. 목록을 읽을 때마다 저절로 돌아갑니다. */
function rotate_() {
  var sh = sheet_('신청');
  var values = sh.getDataRange().getValues();
  var cfg = config_();
  var slots = Number(cfg['자리수'] || 3);
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
    var st = String(values[j][9]).trim();
    if (st !== '예약' && st !== '대기') continue;
    var end = new Date(now.getTime() + FREE_DAYS * 24 * 3600 * 1000);
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

/**
 * 앱 안에서 들어온 클랜 등록. 승인 없이 바로 노출되고(자리가 차면 대기), 아래 검사를 통과해야 해요.
 * 한꺼번에 여러 명이 눌러도 자리가 겹치지 않게 잠금을 걸어요.
 */
function apply_(p) {
  var lock = LockService.getScriptLock();
  try { lock.waitLock(20000); } catch (err) { return text_('지금 사람이 많아요. 잠시 뒤 다시 해 주세요'); }
  try {
    return applyLocked_(p);
  } finally {
    lock.releaseLock();
  }
}
function applyLocked_(p) {
  var data;
  try { data = JSON.parse(p.data || '{}'); } catch (err) { return text_('내용을 읽지 못했어요'); }
  var name = plain_(data.name, 20), line = plain_(data.line, 60), when = plain_(data.when, 30);
  var tag = plain_(data.tag, 40), contact = plain_(data.contact, 60);
  var chat = okLink_(data.chat), discord = okLink_(data.discord);
  if (name.length < 2) return text_('클랜명을 2글자 이상 입력해 주세요');
  if (!chat && !discord && !tag) return text_('오픈채팅, 디스코드, 배틀태그 중 하나는 넣어 주세요');
  // 글 칸에는 링크·연락처를 넣을 수 없어요 (가입 링크는 전용 칸에만)
  var texts = [name, line, when, tag];
  for (var t = 0; t < texts.length; t++) {
    if (hasLink_(texts[t])) return text_('클랜명·소개·시간·배틀태그 칸에는 링크나 주소를 넣을 수 없어요. 링크는 가입 채널 칸에 넣어 주세요');
    if (hasBad_(texts[t])) return text_('사용할 수 없는 표현이 들어 있어요. 고쳐서 다시 등록해 주세요');
  }
  if (tag && !/^[^#\s]{2,24}#\d{3,6}$/.test(tag)) return text_('배틀태그는 이름#1234 모양으로 넣어 주세요');

  var token = String(p.token || '').trim();
  if (token.length < 8) return text_('등록하지 못했어요. 앱을 다시 열고 시도해 주세요');
  var sh = sheet_('신청');
  var values = sh.getDataRange().getValues();
  var cfg = config_();
  var now = Date.now(), dayAgo = now - 24 * 3600 * 1000, today = 0, live = 0;
  for (var i = 1; i < values.length; i++) {
    var state = String(values[i][9]).trim();
    if (state === '노출') live++;
    if (values[i][0] && new Date(values[i][0]).getTime() > dayAgo) today++;
    if (String(values[i][1]).trim() === token) {
      // 한 폰은 한 번에 한 클랜만. 끝났거나 숨겨진 뒤에는 다시 등록할 수 있어요
      if (state === '노출' || state === '예약' || state === '대기') return text_('이미 등록한 클랜이 노출 중이거나 대기 중이에요. 끝난 뒤 다시 등록해 주세요');
      if (values[i][0] && new Date(values[i][0]).getTime() > now - 10 * 60 * 1000) return text_('방금 등록하셨어요. 잠시 뒤에 다시 해 주세요');
    }
    var alive = state === '노출' || state === '예약' || state === '대기';
    if (alive && String(values[i][2]).trim().toLowerCase() === name.toLowerCase()) return text_('같은 이름의 클랜이 이미 등록돼 있어요');
    if (alive && ((chat && String(values[i][5]).trim() === chat) || (discord && String(values[i][6]).trim() === discord))) {
      return text_('같은 가입 링크로 이미 등록된 클랜이 있어요');
    }
  }
  if (today >= Number(cfg['하루신청한도'] || 10)) return text_('오늘은 등록이 많아 마감됐어요. 내일 다시 등록해 주세요');

  var slots = Number(cfg['자리수'] || 3);
  var started = live < slots;
  var begin = started ? new Date() : '';
  var end = started ? new Date(Date.now() + FREE_DAYS * 24 * 3600 * 1000) : '';
  sh.appendRow([new Date(), token, name, line, when, chat, discord, tag, contact.slice(0, 60),
    started ? '노출' : '예약', begin, end, 0, 0, '']);
  notify_('새 클랜 등록: ' + name, (started ? '바로 노출을 시작했어요.' : '자리가 없어 대기에 들어갔어요.') + ' 문제가 있으면 앱 관리자 화면에서 내릴 수 있어요.');
  return text_(started ? '등록됐어요! 지금 바로 노출 중이에요 (' + FREE_DAYS + '일)' : '등록됐어요. 자리가 차 있어 대기에 들어갔고, 자리가 나면 저절로 시작돼요.');
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

/**
 * 사용자의 신고. 서로 다른 폰 3대가 신고하면 노출 중이어도 자동으로 숨겨요 (같은 폰의 중복 신고는 한 번만 세요).
 * 잘못 숨겨졌으면 관리자 화면에서 [되살리기]를 누르면 돼요.
 */
function report_(p) {
  var id = Number(p.id || 0);
  var who = String(p.token || '').trim();
  if (!id || who.length < 8) return text_('');
  var sh = sheet_('신청');
  if (id < 2 || id > sh.getLastRow()) return text_('');
  var rep = sheet_('신고');
  var seen = {}, n = 0;
  rep.getDataRange().getValues().forEach(function (r, i) {
    if (i && Number(r[1]) === id) { seen[String(r[3])] = true; }
  });
  if (seen[who]) return text_('이미 신고하셨어요. 확인할게요.');
  rep.appendRow([new Date(), id, plain_(p.reason, 200), who]);
  for (var k in seen) n++;
  var count = n + 1;
  var row = sh.getRange(id, 1, 1, SHEETS.apply.length).getValues()[0];
  sh.getRange(id, 14).setValue(count);
  if (count >= REPORT_LIMIT && String(row[9]).trim() !== '종료' && String(row[9]).trim() !== '거절') {
    sh.getRange(id, 10).setValue('거절');
    sh.getRange(id, 15).setValue('신고가 쌓여 자동으로 숨겨졌어요');
  }
  notify_('클랜 신고 ' + count + '건: ' + row[2], plain_(p.reason, 200));
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
    return json_({ items: out, slots: Number(cfg['자리수'] || 3), days: FREE_DAYS, paid: false });
  }
  if (!id) return text_('');

  if (act === 'approve') {
    // 빈 자리가 있으면 바로 노출, 없으면 예약(선착순 대기)
    var live = 0;
    rows_('신청').forEach(function (r) { if (String(r[9]).trim() === '노출') live++; });
    if (live < Number(cfg['자리수'] || 3)) {
      var now = new Date();
      var end = new Date(now.getTime() + FREE_DAYS * 24 * 3600 * 1000);
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
    // 이 클랜에 쌓인 신고를 지워야 되살린 뒤 바로 다시 숨겨지지 않아요
    var rep = sheet_('신고'), rv = rep.getDataRange().getValues();
    for (var q = rv.length - 1; q >= 1; q--) if (Number(rv[q][1]) === id) rep.deleteRow(q + 1);
    sh.getRange(id, 10).setValue('예약');
    sh.getRange(id, 14).setValue(0);
    sh.getRange(id, 15).setValue('');
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
