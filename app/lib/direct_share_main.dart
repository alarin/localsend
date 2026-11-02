import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:flutter/services.dart';
import 'package:localsend_app/config/init.dart';
import 'package:localsend_app/model/cross_file.dart';
import 'package:localsend_app/provider/device_info_provider.dart';
import 'package:localsend_app/provider/favorites_provider.dart';
import 'package:localsend_app/provider/http_provider.dart';
import 'package:localsend_app/provider/network/send_provider.dart';
import 'package:localsend_app/provider/settings_provider.dart';
import 'package:localsend_app/util/headless.dart';
import 'package:localsend_app/util/native/channel/android_direct_share_channel.dart';
import 'package:localsend_isolates/model/device.dart';
import 'package:localsend_isolates/model/file_type.dart';
import 'package:localsend_isolates/model/session_status.dart';
import 'package:localsend_isolates/rust/api/model.dart';
import 'package:localsend_isolates/util/file_path_helper.dart';
import 'package:localsend_isolates/util/rust.dart';
import 'package:logging/logging.dart';
import 'package:refena_flutter/refena_flutter.dart';

final _logger = Logger('DirectShare');

const _channel = MethodChannel('org.localsend.localsend_app/direct_share');

/// Headless entrypoint used by Android direct share targets (per-device rows in the
/// system share sheet). Runs without any widgets: resolves the target device from the
/// published shortcut, performs a background send and reports the outcome back to the
/// native foreground service, which then kills this process.
@pragma('vm:entry-point')
Future<void> directShareMain() async {
  isHeadlessDirectShare = true;

  final container = await preInit(const []);

  try {
    await _runDirectShare(container);
  } catch (e, st) {
    _logger.severe('Direct share failed', e, st);
    await _reportDone(success: false, message: e.toString());
  }
}

Future<void> _runDirectShare(RefenaContainer ref) async {
  final payload = await _channel.invokeMethod<Map>('getPayload');
  if (payload == null) {
    throw StateError('No share payload');
  }

  final fingerprint = payload['fingerprint'] as String?;
  final text = payload['text'] as String?;
  final rawFiles = (payload['files'] as List?)?.cast<Map>() ?? [];

  final files = <CrossFile>[
    for (final raw in rawFiles)
      CrossFile(
        name: raw['name'] as String,
        fileType: (raw['name'] as String).guessFileType(),
        size: (raw['size'] as int?) ?? 0,
        thumbnail: null,
        asset: null,
        // The native side copies the shared content into the cache, so these are
        // plain file paths; they are deleted once the session is over.
        path: raw['path'] as String,
        bytes: null,
        lastModified: null,
        lastAccessed: null,
      ),
    if (text != null && text.trim().isNotEmpty)
      CrossFile(
        name: 'message.txt',
        fileType: FileType.text,
        size: utf8.encode(text).length,
        thumbnail: null,
        asset: null,
        path: null,
        bytes: utf8.encode(text),
        lastModified: null,
        lastAccessed: null,
      ),
  ];

  if (files.isEmpty) {
    await _reportDone(success: false, message: 'Nothing to send');
    return;
  }

  try {
    await _sendToFavorite(ref, fingerprint, files);
  } finally {
    for (final f in files) {
      final path = f.path;
      if (path != null) {
        try {
          await File(path).delete();
        } catch (_) {}
      }
    }
  }
}

Future<void> _sendToFavorite(RefenaContainer ref, String? fingerprint, List<CrossFile> files) async {

  final favorites = ref.read(favoritesProvider);
  final favorite = favorites.where((f) => f.fingerprint == fingerprint).firstOrNull;
  if (favorite == null) {
    await _reportDone(success: false, message: 'Unknown device: $fingerprint (favorites: ${favorites.length})');
    return;
  }

  await _updateNotification(title: 'LocalSend', text: 'Connecting to ${favorite.alias}…');

  final Device device;
  try {
    final https = ref.read(settingsProvider).https;
    final response = await ref
        .read(httpProvider)
        .discovery
        .register(
          protocol: https ? ProtocolType.https : ProtocolType.http,
          ip: favorite.ip,
          port: favorite.port,
          payload: ref.read(deviceFullInfoProvider).toRegisterDto(),
        );
    device = response.body.toDevice(favorite.ip, favorite.port, https);
  } catch (e) {
    _logger.warning('Device not reachable at ${favorite.ip}:${favorite.port}', e);
    await _reportDone(success: false, message: '${favorite.alias} is not reachable');
    return;
  }

  await _updateNotification(title: 'LocalSend', text: 'Sending to ${favorite.alias}…');

  final sessionsBefore = ref.read(sendProvider).keys.toSet();
  await ref.notifier(sendProvider).startSession(
        target: device,
        files: files,
        background: true,
      );

  final newSessions = ref.read(sendProvider).keys.where((id) => !sessionsBefore.contains(id));
  final session = newSessions.isEmpty ? null : ref.read(sendProvider)[newSessions.first];

  if (session == null) {
    // The session closed itself: background sessions are removed on success only.
    await _reportDone(success: true, message: 'Sent to ${favorite.alias}');
    return;
  }

  switch (session.status) {
    case SessionStatus.finished:
      await _reportDone(success: true, message: 'Sent to ${favorite.alias}');
    case SessionStatus.declined:
      await _reportDone(success: false, message: '${favorite.alias} declined');
    case SessionStatus.recipientBusy:
      await _reportDone(success: false, message: '${favorite.alias} is busy');
    case SessionStatus.canceledByReceiver:
      await _reportDone(success: false, message: '${favorite.alias} canceled');
    case SessionStatus.canceledBySender:
      await _reportDone(success: false, message: 'Canceled');
    case SessionStatus.tooManyAttempts:
      await _reportDone(success: false, message: 'Too many attempts');
    case SessionStatus.finishedWithErrors:
      await _reportDone(success: false, message: session.errorMessage ?? 'Sending failed');
    default:
      await _reportDone(success: false, message: 'Sending failed');
  }
}

Future<void> _updateNotification({required String title, required String text}) {
  return updateDirectShareNotificationAndroid(title: title, text: text);
}

Future<void> _reportDone({required bool success, String? message}) async {
  try {
    await _channel.invokeMethod('done', {'success': success, 'message': message});
  } catch (e) {
    _logger.warning('Could not report direct share result', e);
  }
}
