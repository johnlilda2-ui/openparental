import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/models.dart';
import '../state/providers.dart';
import '../theme/app_theme.dart';
import '../widgets/ui.dart';

/// Latest reported location for one device, with a jump into Maps.
class LocationScreen extends ConsumerStatefulWidget {
  final Device device;
  const LocationScreen({super.key, required this.device});

  @override
  ConsumerState<LocationScreen> createState() => _LocationScreenState();
}

class _LocationScreenState extends ConsumerState<LocationScreen> {
  bool _loading = true;
  DeviceLocation? _location;
  String? _message;

  @override
  void initState() {
    super.initState();
    _refreshLocation();
  }

  /// Request a fresh location on the child device, wait briefly for its
  /// reported result, then reload the server's latest saved coordinate.
  Future<void> _refreshLocation() async {
    setState(() {
      _loading = true;
      _message = null;
    });

    final api = ref.read(apiProvider);
    String? message;

    try {
      final submitted =
          await api.sendCommand(widget.device.id, 'REQUEST_LOCATION');
      CommandStatus? status;
      try {
        status = await api.waitForCommand(submitted);
      } catch (_) {
        message =
            'The request was queued, but the child device has not confirmed it yet.';
      }

      if (status?.state == 'FAILED') {
        message = 'Location request failed: '
            '${status?.error ?? 'Check location permission and device settings.'}';
      } else if (status?.state == 'EXPIRED') {
        message = 'The location request expired before the device responded.';
      } else if (status != null && !status.isTerminal) {
        message =
            'Location request is still queued. The displayed position may be older.';
      }
    } catch (e) {
      message = 'Could not send a location request: $e';
    }

    DeviceLocation? latest;
    try {
      latest = await api.latestLocation(widget.device.id);
    } catch (e) {
      message ??= 'Could not load the latest saved location: $e';
    }

    if (mounted) {
      setState(() {
        _location = latest;
        _message = message;
        _loading = false;
      });
    }
  }

  Future<void> _openMaps(double lat, double lng) async {
    final uri =
        Uri.parse('https://www.google.com/maps/search/?api=1&query=$lat,$lng');
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final loc = _location;

    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.device.name} — location'),
        actions: [
          IconButton(
            onPressed: _loading ? null : _refreshLocation,
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Request fresh location',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              children: [
                if (_message != null)
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.info_outline_rounded),
                      title: Text(_message!),
                    ),
                  ),
                if (loc == null)
                  const EmptyState(
                    icon: Icons.location_off_outlined,
                    title: 'No location yet',
                    subtitle: 'The child device has not uploaded a location. '
                        'Check that location services and permission are enabled.',
                  )
                else ...[
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(18),
                      child: Row(
                        children: [
                          const SoftIcon(Icons.location_on_rounded,
                              AppColors.online, size: 48),
                          const SizedBox(width: 14),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '${loc.lat.toStringAsFixed(5)}, '
                                  '${loc.lng.toStringAsFixed(5)}',
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  '${loc.occurredAt != null ? DateFormat('MMM d, HH:mm').format(loc.occurredAt!.toLocal()) : ''}'
                                  '${loc.accuracyM != null ? ' · ±${loc.accuracyM!.round()}m' : ''}',
                                  style: TextStyle(
                                      color: scheme.onSurfaceVariant,
                                      fontSize: 13),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    onPressed: () => _openMaps(loc.lat, loc.lng),
                    icon: const Icon(Icons.map_rounded),
                    label: const Text('Open in Maps'),
                  ),
                ],
              ],
            ),
    );
  }
}
