const REGISTRY_FOLDER_NAME = 'GeoStampRegistry';

function doPost(e) {
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const evidenceId = String(body.evidenceId || body.verificationId || '').trim().toUpperCase();
    if (!evidenceId) return json_({ok:false,error:'Evidence ID required'}, 400);

    // Visual evidence is mandatory for newly published records.
    const visualRequired = body.visualEvidenceRequired !== false;
    const thumb = String(body.thumbnailBase64 || body.thumbnailJpegBase64 || '').trim();
    if (visualRequired && !thumb) {
      return json_({ok:false,error:'Mandatory evidence thumbnail missing'}, 422);
    }

    body.evidenceId = evidenceId;
    body.verificationId = evidenceId;
    body.registryStatus = 'PUBLIC_RECORD';
    body.publishedAt = Date.now();
    body.publisher = 'GeoStamp Automatic Registry';

    const folder = registryFolder_();
    const fileName = safe_(evidenceId) + '.json';
    const files = folder.getFilesByName(fileName);
    const text = JSON.stringify(body, null, 2);
    let file;
    if (files.hasNext()) {
      file = files.next();
      file.setContent(text);
    } else {
      file = folder.createFile(fileName, text, MimeType.PLAIN_TEXT);
    }

    return json_({
      ok:true,
      message:'Registered',
      evidenceId:evidenceId,
      registryUrl: ScriptApp.getService().getUrl() + '?id=' + encodeURIComponent(evidenceId)
    });
  } catch (err) {
    return json_({ok:false,error:String(err && err.message || err)}, 500);
  }
}

function doGet(e) {
  try {
    const id = String((e && e.parameter && e.parameter.id) || '').trim().toUpperCase();
    if (!id) return json_({ok:true,service:'GeoStamp Registry'});
    const folder = registryFolder_();
    const files = folder.getFilesByName(safe_(id) + '.json');
    if (!files.hasNext()) return json_({ok:false,error:'NOT_FOUND'}, 404);
    const record = JSON.parse(files.next().getBlob().getDataAsString('UTF-8'));
    return json_({ok:true,record:record});
  } catch (err) {
    return json_({ok:false,error:String(err && err.message || err)}, 500);
  }
}

function registryFolder_() {
  const props = PropertiesService.getScriptProperties();
  const knownId = props.getProperty('GEOSTAMP_REGISTRY_FOLDER_ID');
  if (knownId) {
    try { return DriveApp.getFolderById(knownId); } catch (_) {}
  }
  const folders = DriveApp.getFoldersByName(REGISTRY_FOLDER_NAME);
  const folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(REGISTRY_FOLDER_NAME);
  props.setProperty('GEOSTAMP_REGISTRY_FOLDER_ID', folder.getId());
  return folder;
}

function safe_(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
