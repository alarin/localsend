import 'package:flutter/services.dart';
import 'package:logging/logging.dart';

final _logger = Logger('AndroidDirectShare');

const _channel = MethodChannel('org.localsend.localsend_app/direct_share');

/// Progress update for the native foreground service notification of the
/// headless direct share engine. No-op outside that engine (the native side
/// simply has no service to update).
Future<void> updateDirectShareNotificationAndroid({required String title, required String text}) async {
  try {
    await _channel.invokeMethod('updateNotification', {'title': title, 'text': text});
  } catch (e) {
    _logger.warning('Could not update direct share notification', e);
  }
}
