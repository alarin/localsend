import 'package:flutter/foundation.dart';
import 'package:localsend_app/model/persistence/favorite_device.dart';
import 'package:localsend_app/provider/persistence_provider.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_app/util/native/platform_check.dart';
import 'package:refena_flutter/refena_flutter.dart';

/// Keeps the Android direct share targets (one share sheet row per favorite)
/// in sync with the persisted favorites.
void syncDirectShareShortcuts(List<FavoriteDevice> favorites) {
  if (!checkPlatform([TargetPlatform.android])) {
    return;
  }
  // ignore: discarded_futures
  updateDirectShareShortcutsAndroid([
    for (final f in favorites) (fingerprint: f.fingerprint, alias: f.alias),
  ]);
}

/// This provider stores the list of favorite devices.
/// It automatically saves the list to the device's storage.
final favoritesProvider = ReduxProvider<FavoritesService, List<FavoriteDevice>>((ref) {
  return FavoritesService(ref.read(persistenceProvider));
});

class FavoritesService extends ReduxNotifier<List<FavoriteDevice>> {
  final PersistenceService _persistence;

  FavoritesService(this._persistence);

  @override
  List<FavoriteDevice> init() => _persistence.getFavorites();
}

/// Adds a favorite device.
class AddFavoriteAction extends AsyncReduxAction<FavoritesService, List<FavoriteDevice>> {
  final FavoriteDevice device;

  AddFavoriteAction(this.device);

  @override
  Future<List<FavoriteDevice>> reduce() async {
    final updated = List<FavoriteDevice>.unmodifiable([
      ...state,
      device,
    ]);
    await notifier._persistence.setFavorites(updated);
    syncDirectShareShortcuts(updated);
    return updated;
  }
}

/// Updates a favorite device.
class UpdateFavoriteAction extends AsyncReduxAction<FavoritesService, List<FavoriteDevice>> {
  final FavoriteDevice device;

  UpdateFavoriteAction(this.device);

  @override
  Future<List<FavoriteDevice>> reduce() async {
    final index = state.indexWhere((e) => e.id == device.id);
    if (index == -1) {
      // Unknown device
      await Future.microtask(() {});
      return state;
    }
    final updated = List<FavoriteDevice>.unmodifiable(
      <FavoriteDevice>[
        ...state,
      ]..replaceRange(index, index + 1, [device]),
    );
    await notifier._persistence.setFavorites(updated);
    syncDirectShareShortcuts(updated);
    return updated;
  }
}

/// Removes a favorite device.
class RemoveFavoriteAction extends AsyncReduxAction<FavoritesService, List<FavoriteDevice>> {
  final String deviceFingerprint;

  RemoveFavoriteAction({
    required this.deviceFingerprint,
  });

  @override
  Future<List<FavoriteDevice>> reduce() async {
    final index = state.indexWhere((e) => e.fingerprint == deviceFingerprint);
    if (index == -1) {
      // Unknown device
      return state;
    }
    final updated = List<FavoriteDevice>.unmodifiable(
      <FavoriteDevice>[
        ...state,
      ]..removeAt(index),
    );
    await notifier._persistence.setFavorites(updated);
    syncDirectShareShortcuts(updated);
    return updated;
  }
}
