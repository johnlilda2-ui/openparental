import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/models.dart';
import '../state/providers.dart';
import '../theme/app_theme.dart';
import '../widgets/ui.dart';

/// Shows the latest location and supports an explicit, status-tracked request.
class LocationScreen extends ConsumerStatefulWidget {
  final Device device;
  const LocationScreen({super.key, required this.device});

  @override
  ConsumerState<LocationScreen> createState() => _LocationScreenState();
}

class _LocationScreenState extends ConsumerState<LocationScreen> {
  bool _loading = true;
  bool _requesting = false;
  bool _requestFailed = false;
  String? _requestMessage;
  DeviceLocation? _location;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (mounted) setState(() => _loading = true);
    DeviceLocation? loc;
    try {
      loc = await ref.read(apiProvider).latestLocation(widget.device.id);
    } catch (_) {}
    if (mounted) {
      setState(() {
        _location = loc;
        _loading = false;
      });
    }
  }

  Future<void> _requestLocation() async {
    if (_requesting) return;
    setState(() {
      _requesting = true;
      _requestFailed = false;
      _requestMessage = 'Sending a location request to the child device…';
    });

    try {
      final api = ref.read(apiProvider);
      var command = await api.sendCommand(widget.device.id, 'REQUEST_LOCATION');
      final deadline = DateTime.now().add(const Duration(seconds: 65));

      while (!_isTerminal(command.state) && DateTime.now().isBefore(deadline)) {
        if (mounted) {
          setState(() {
            _requestMessage =
                'Waiting for the child device… (${command.state.toLowerCase()})';
          });
        }
        await Future<void>.delayed(const Duration(seconds: 2));
        if (!mounted) return;
        command = await api.commandStatus(command.id);
      }

      if (!mounted) return;
      switch (command.state) {
        case 'DONE':
          await _load();
          if (!mounted) return;
          setState(() {
            _requestFailed = false;
            _requestMessage = 'The device reported a location successfully.';
          });
          break;
        case 'FAILED':
          setState(() {
            _requestFailed = true;
            _requestMessage =
                command.error ?? 'The child device could not report its location.';
          });
          break;
        case 'EXPIRED':
          setState(() {
            _requestFailed = true;
            _requestMessage =
                command.error ?? 'The request expired before the device completed it.';
          });
          break;
        default:
          setState(() {
            _requestFailed = true;
            _requestMessage =
                'The request is still pending. Check the child device connection and try again.';
          });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _requestFailed = true;
          _requestMessage = 'Could not complete the location request: $error';
        });
      }
    } finally {
      if (mounted) setState(() => _requesting = false);
    }
  }

  bool _isTerminal(String state) =>
      state == 'DONE' || state == 'FAILED' || state == 'EXPIRED';

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
            onPressed: (_loading || _requesting) ? null : _load,
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Refresh latest stored location',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              children: [
                Text(
                  'Location is request-based. The child device must be online, '
                  'have location enabled, and grant the required background '
                  'location permission.',
                  style: TextStyle(
                    color: scheme.onSurfaceVariant,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: _requesting ? null : _requestLocation,
                  icon: _requesting
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.my_location_rounded),
                  label: Text(
                    _requesting ? 'Request in progress…' : 'Request current location',
                  ),
                ),
                if (_requesting) ...[
                  const SizedBox(height: 12),
                  const LinearProgressIndicator(),
                ],
                if (_requestMessage != null) ...[
                  const SizedBox(height: 10),
                  Text(
                    _requestMessage!,
                    style: TextStyle(
                      color: _requestFailed ? scheme.error : scheme.primary,
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                if (loc == null)
                  const EmptyState(
                    icon: Icons.location_off_outlined,
                    title: 'No location recorded',
                    subtitle:
                        'Request the child device’s current location to retrieve a fresh fix.',
                  )
                else ...[
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(18),
                      child: Row(
                        children: [
                          const SoftIcon(
                            Icons.location_on_rounded,
                            AppColors.online,
                            size: 48,
                          ),
                          const SizedBox(width: 14),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '${loc.lat.toStringAsFixed(5)}, '
                                  '${loc.lng.toStringAsFixed(5)}',
                                  style: Theme.of(context).textTheme.titleMedium,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  '${loc.occurredAt != null ? DateFormat('MMM d, HH:mm').format(loc.occurredAt!.toLocal()) : 'Time unavailable'}'
                                  '${loc.accuracyM != null ? ' · ±${loc.accuracyM!.round()}m' : ''}',
                                  style: TextStyle(
                                    color: scheme.onSurfaceVariant,
                                    fontSize: 13,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
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
