import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Color(0xFF121212),
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Color(0xFF121212),
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const AutopilotProGamerApp());
}

class AutopilotProGamerApp extends StatelessWidget {
  const AutopilotProGamerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Autopilot Pro-Gamer AI',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF121212),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF10A37F),
          secondary: Color(0xFFEF4444),
          surface: Color(0xFF1E1E1E),
          onSurface: Color(0xFFECECEC),
        ),
        useMaterial3: true,
        fontFamily: 'Roboto',
      ),
      home: const AutopilotHomeScreen(),
    );
  }
}

class InstalledAppInfo {
  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
  final bool isGame;

  const InstalledAppInfo({
    required this.appName,
    required this.packageName,
    required this.iconBytes,
    required this.isGame,
  });

  factory InstalledAppInfo.fromMap(Map<dynamic, dynamic> map) {
    Uint8List? decodedIcon;
    final dynamic rawIcon = map['icon'];
    if (rawIcon is Uint8List) {
      decodedIcon = rawIcon;
    } else if (rawIcon is String && rawIcon.isNotEmpty) {
      try {
        decodedIcon = base64Decode(rawIcon);
      } catch (_) {
        decodedIcon = null;
      }
    }

    return InstalledAppInfo(
      appName: (map['appName'] ?? map['name'] ?? 'Unknown App').toString(),
      packageName: (map['packageName'] ?? '').toString(),
      iconBytes: decodedIcon,
      isGame: map['isGame'] == true,
    );
  }
}

class PermissionStatusState {
  final bool overlayGranted;
  final bool accessibilityGranted;
  final bool notificationGranted;
  final bool usageStatsGranted;

  const PermissionStatusState({
    required this.overlayGranted,
    required this.accessibilityGranted,
    required this.notificationGranted,
    required this.usageStatsGranted,
  });

  const PermissionStatusState.initial()
      : overlayGranted = false,
        accessibilityGranted = false,
        notificationGranted = false,
        usageStatsGranted = false;

  factory PermissionStatusState.fromMap(Map<dynamic, dynamic> map) {
    return PermissionStatusState(
      overlayGranted: map['overlay'] == true,
      accessibilityGranted: map['accessibility'] == true,
      notificationGranted: map['notification'] == true,
      usageStatsGranted: map['usageStats'] ?? true,
    );
  }

  bool get allRequiredGranted =>
      overlayGranted && accessibilityGranted && notificationGranted;
}

enum ChatEntryType {
  userDirective,
  assistantInfo,
  actionCard,
}

class ChatEntry {
  final String id;
  final ChatEntryType type;
  final String text;
  final InstalledAppInfo? app;
  final DateTime timestamp;

  const ChatEntry({
    required this.id,
    required this.type,
    required this.text,
    this.app,
    required this.timestamp,
  });
}

class EngineChannelBridge {
  static const MethodChannel _methodChannel =
      MethodChannel('com.autopilot.pro_gamer/engine');
  static const EventChannel _stateEventChannel =
      EventChannel('com.autopilot.pro_gamer/engine_state');

  static Stream<Map<dynamic, dynamic>> get engineStateStream {
    return _stateEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => event as Map<dynamic, dynamic>);
  }

  static Future<List<InstalledAppInfo>> getInstalledApps() async {
    final List<dynamic>? result =
        await _methodChannel.invokeMethod<List<dynamic>>('getInstalledApps');
    if (result == null) return <InstalledAppInfo>[];
    final List<InstalledAppInfo> apps = result
        .whereType<Map<dynamic, dynamic>>()
        .map(InstalledAppInfo.fromMap)
        .where((app) => app.packageName.isNotEmpty)
        .toList();

    apps.sort((a, b) {
      if (a.isGame != b.isGame) {
        return a.isGame ? -1 : 1;
      }
      return a.appName.toLowerCase().compareTo(b.appName.toLowerCase());
    });
    return apps;
  }

  static Future<PermissionStatusState> checkPermissions() async {
    final Map<dynamic, dynamic>? result = await _methodChannel
        .invokeMethod<Map<dynamic, dynamic>>('checkPermissions');
    if (result == null) {
      return const PermissionStatusState.initial();
    }
    return PermissionStatusState.fromMap(result);
  }

  static Future<void> requestOverlayPermission() async {
    await _methodChannel.invokeMethod<void>('requestOverlayPermission');
  }

  static Future<void> openAccessibilitySettings() async {
    await _methodChannel.invokeMethod<void>('openAccessibilitySettings');
  }

  static Future<void> requestNotificationPermission() async {
    await _methodChannel.invokeMethod<void>('requestNotificationPermission');
  }

  static Future<bool> isEngineRunning() async {
    final bool? running =
        await _methodChannel.invokeMethod<bool>('isEngineRunning');
    return running ?? false;
  }

  static Future<bool> startEngine({
    required String packageName,
    required String appName,
    required String directive,
  }) async {
    final bool? started = await _methodChannel.invokeMethod<bool>(
      'startEngine',
      <String, dynamic>{
        'packageName': packageName,
        'appName': appName,
        'directive': directive,
      },
    );
    return started ?? false;
  }

  static Future<void> stopEngine() async {
    await _methodChannel.invokeMethod<void>('stopEngine');
  }
}

class AutopilotHomeScreen extends StatefulWidget {
  const AutopilotHomeScreen({super.key});

  @override
  State<AutopilotHomeScreen> createState() => _AutopilotHomeScreenState();
}

class _AutopilotHomeScreenState extends State<AutopilotHomeScreen>
    with WidgetsBindingObserver {
  final TextEditingController _directiveController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  PermissionStatusState _permissions = const PermissionStatusState.initial();
  InstalledAppInfo? _selectedApp;
  String _activeDirective =
      'Play aggressively, maximize damage, and use smart cover';
  bool _isEngineRunning = false;
  bool _isStartingOrStopping = false;
  bool _isLoadingApps = false;
  String? _statusMessage;

  final List<ChatEntry> _entries = <ChatEntry>[];
  StreamSubscription<Map<dynamic, dynamic>>? _engineSubscription;

  static const List<String> _presetDirectives = <String>[
    'Play aggressively, maximize damage, and use smart cover',
    'Play defensively, prioritize survival, loot medkits, and hold high ground',
    'Rush close-range fights with fast gloo wall shielding and jump-shots',
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _directiveController.text = _activeDirective;
    _seedInitialMessages();
    _refreshNativeState();
    _listenToEngineEvents();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _engineSubscription?.cancel();
    _directiveController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshNativeState();
    }
  }

  void _seedInitialMessages() {
    _entries.add(
      ChatEntry(
        id: 'welcome',
        type: ChatEntryType.assistantInfo,
        text:
            'Autopilot Pro-Gamer AI ready. Tap + to select an installed game, set your single tactical directive, and tap START to launch autonomous gameplay.',
        timestamp: DateTime.now(),
      ),
    );
  }

  void _listenToEngineEvents() {
    try {
      _engineSubscription = EngineChannelBridge.engineStateStream.listen(
        (Map<dynamic, dynamic> event) {
          if (!mounted) return;
          final bool running = event['running'] == true;
          final String? reason = event['message']?.toString();
          setState(() {
            _isEngineRunning = running;
            _isStartingOrStopping = false;
            if (reason != null && reason.isNotEmpty) {
              _statusMessage = reason;
            }
          });
        },
        onError: (_) {},
      );
    } catch (_) {}
  }

  Future<void> _refreshNativeState() async {
    try {
      final PermissionStatusState perms =
          await EngineChannelBridge.checkPermissions();
      final bool running = await EngineChannelBridge.isEngineRunning();
      if (!mounted) return;
      setState(() {
        _permissions = perms;
        _isEngineRunning = running;
      });
    } catch (_) {}
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _openInstalledAppsPicker() async {
    if (_isLoadingApps) return;
    setState(() {
      _isLoadingApps = true;
      _statusMessage = null;
    });

    List<InstalledAppInfo> apps = <InstalledAppInfo>[];
    String? loadError;

    try {
      apps = await EngineChannelBridge.getInstalledApps();
    } on PlatformException catch (e) {
      loadError = e.message ?? 'Unable to query installed apps.';
    } catch (_) {
      loadError = 'Native app selector channel is not connected.';
    }

    if (!mounted) return;
    setState(() {
      _isLoadingApps = false;
    });

    if (loadError != null) {
      _showSnack(loadError);
      return;
    }

    final InstalledAppInfo? picked =
        await showModalBottomSheet<InstalledAppInfo>(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF1A1A1A),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (BuildContext context) {
        return _InstalledAppsSheet(apps: apps);
      },
    );

    if (picked != null && mounted) {
      _onAppSelected(picked);
    }
  }

  void _onAppSelected(InstalledAppInfo app) {
    final String directive = _directiveController.text.trim().isNotEmpty
        ? _directiveController.text.trim()
        : _activeDirective;

    setState(() {
      _selectedApp = app;
      _activeDirective = directive;
      _entries.removeWhere((e) => e.type == ChatEntryType.actionCard);
      _entries.add(
        ChatEntry(
          id: 'action_${app.packageName}',
          type: ChatEntryType.actionCard,
          text: directive,
          app: app,
          timestamp: DateTime.now(),
        ),
      );
    });
    _scrollToBottom();
  }

  void _submitDirective() {
    final String text = _directiveController.text.trim();
    if (text.isEmpty) return;

    FocusScope.of(context).unfocus();

    setState(() {
      _activeDirective = text;
      _entries.add(
        ChatEntry(
          id: 'cmd_${DateTime.now().millisecondsSinceEpoch}',
          type: ChatEntryType.userDirective,
          text: text,
          timestamp: DateTime.now(),
        ),
      );

      if (_selectedApp != null) {
        _entries.removeWhere((e) => e.type == ChatEntryType.actionCard);
        _entries.add(
          ChatEntry(
            id: 'action_${_selectedApp!.packageName}',
            type: ChatEntryType.actionCard,
            text: text,
            app: _selectedApp,
            timestamp: DateTime.now(),
          ),
        );
      } else {
        _entries.add(
          ChatEntry(
            id: 'prompt_pick_${DateTime.now().millisecondsSinceEpoch}',
            type: ChatEntryType.assistantInfo,
            text:
                'Directive saved. Tap the + button below to pick the target game and generate its Action Card.',
            timestamp: DateTime.now(),
          ),
        );
      }
    });
    _scrollToBottom();
  }

  Future<void> _toggleEngine() async {
    if (_isStartingOrStopping) return;

    if (_isEngineRunning) {
      setState(() => _isStartingOrStopping = true);
      try {
        await EngineChannelBridge.stopEngine();
        if (!mounted) return;
        setState(() {
          _isEngineRunning = false;
          _statusMessage = 'AI Engine stopped.';
        });
      } on PlatformException catch (e) {
        _showSnack(e.message ?? 'Failed to stop AI Engine.');
      } finally {
        if (mounted) {
          setState(() => _isStartingOrStopping = false);
        }
      }
      return;
    }

    if (_selectedApp == null) {
      _showSnack('Select a game using the + button first.');
      return;
    }

    await _refreshNativeState();
    if (!_permissions.allRequiredGranted) {
      if (!mounted) return;
      _openPermissionsSheet();
      return;
    }

    final String directive = _directiveController.text.trim().isNotEmpty
        ? _directiveController.text.trim()
        : _activeDirective;

    setState(() {
      _activeDirective = directive;
      _isStartingOrStopping = true;
      _statusMessage = null;
    });

    try {
      final bool started = await EngineChannelBridge.startEngine(
        packageName: _selectedApp!.packageName,
        appName: _selectedApp!.appName,
        directive: directive,
      );
      if (!mounted) return;
      setState(() {
        _isEngineRunning = started;
        _statusMessage = started
            ? 'Autopilot active. Launching ${_selectedApp!.appName}...'
            : 'Screen capture permission is required to start.';
      });
    } on PlatformException catch (e) {
      _showSnack(e.message ?? 'Failed to launch AI Engine.');
    } finally {
      if (mounted) {
        setState(() => _isStartingOrStopping = false);
      }
    }
  }

  Future<void> _openPermissionsSheet() async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF1A1A1A),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter modalSetState) {
            Future<void> refreshModal() async {
              await _refreshNativeState();
              modalSetState(() {});
            }

            return SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        decoration: BoxDecoration(
                          color: const Color(0xFF3A3A3A),
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      'Required Android Permissions',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFFECECEC),
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      'Grant these permissions so the AI engine can display the status dot, read HUD layout, and show the persistent notification toggle.',
                      style: TextStyle(
                        fontSize: 13,
                        color: Color(0xFF9E9E9E),
                      ),
                    ),
                    const SizedBox(height: 16),
                    _PermissionTile(
                      icon: Icons.layers_outlined,
                      title: 'Display Over Other Apps',
                      subtitle: 'Floating red status dot (SYSTEM_ALERT_WINDOW)',
                      granted: _permissions.overlayGranted,
                      onGrant: () async {
                        await EngineChannelBridge.requestOverlayPermission();
                        await refreshModal();
                      },
                    ),
                    const SizedBox(height: 10),
                    _PermissionTile(
                      icon: Icons.touch_app_outlined,
                      title: 'Accessibility Touch Service',
                      subtitle: 'Autonomous HUD control execution',
                      granted: _permissions.accessibilityGranted,
                      onGrant: () async {
                        await EngineChannelBridge.openAccessibilitySettings();
                        await refreshModal();
                      },
                    ),
                    const SizedBox(height: 10),
                    _PermissionTile(
                      icon: Icons.notifications_active_outlined,
                      title: 'Persistent Notifications',
                      subtitle: 'One-tap Turn OFF AI Engine notification toggle',
                      granted: _permissions.notificationGranted,
                      onGrant: () async {
                        await EngineChannelBridge
                            .requestNotificationPermission();
                        await refreshModal();
                      },
                    ),
                    const SizedBox(height: 16),
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFF2B2B2B),
                          foregroundColor: const Color(0xFFECECEC),
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        onPressed: () async {
                          await refreshModal();
                          if (context.mounted &&
                              _permissions.allRequiredGranted) {
                            Navigator.of(context).pop();
                          }
                        },
                        child: Text(
                          _permissions.allRequiredGranted
                              ? 'All Permissions Granted'
                              : 'Refresh Permission Status',
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: const Color(0xFF262626),
          behavior: SnackBarBehavior.floating,
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF121212),
      appBar: AppBar(
        backgroundColor: const Color(0xFF121212),
        elevation: 0,
        scrolledUnderElevation: 0,
        titleSpacing: 16,
        title: Row(
          children: <Widget>[
            Container(
              width: 10,
              height: 10,
              decoration: BoxDecoration(
                color: _isEngineRunning
                    ? const Color(0xFFEF4444)
                    : const Color(0xFF10A37F),
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: Text(
                'Pro-Gamer AI',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFFECECEC),
                ),
              ),
            ),
          ],
        ),
        actions: <Widget>[
          IconButton(
            tooltip: 'Permissions',
            onPressed: _openPermissionsSheet,
            icon: Icon(
              _permissions.allRequiredGranted
                  ? Icons.verified_user_outlined
                  : Icons.gpp_maybe_outlined,
              color: _permissions.allRequiredGranted
                  ? const Color(0xFF10A37F)
                  : const Color(0xFFF59E0B),
            ),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: <Widget>[
            if (!_permissions.allRequiredGranted)
              _PermissionsBanner(
                permissions: _permissions,
                onTapConfigure: _openPermissionsSheet,
              ),
            if (_statusMessage != null)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: const Color(0xFF1E1E1E),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFF2E2E2E)),
                ),
                child: Text(
                  _statusMessage!,
                  style: const TextStyle(
                    fontSize: 12.5,
                    color: Color(0xFFBDBDBD),
                  ),
                ),
              ),
            Expanded(
              child: ListView.builder(
                controller: _scrollController,
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                itemCount: _entries.length,
                itemBuilder: (BuildContext context, int index) {
                  final ChatEntry entry = _entries[index];
                  switch (entry.type) {
                    case ChatEntryType.assistantInfo:
                      return _AssistantBubble(text: entry.text);
                    case ChatEntryType.userDirective:
                      return _UserBubble(text: entry.text);
                    case ChatEntryType.actionCard:
                      return _GameActionCard(
                        app: entry.app!,
                        directive: _activeDirective,
                        isRunning: _isEngineRunning,
                        isBusy: _isStartingOrStopping,
                        onToggle: _toggleEngine,
                        onChangeGame: _openInstalledAppsPicker,
                      );
                  }
                },
              ),
            ),
            if (_selectedApp == null)
              SizedBox(
                height: 40,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  itemCount: _presetDirectives.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (BuildContext context, int index) {
                    final String preset = _presetDirectives[index];
                    return ActionChip(
                      backgroundColor: const Color(0xFF1C1C1C),
                      side: const BorderSide(color: Color(0xFF2D2D2D)),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(18),
                      ),
                      label: Text(
                        preset,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFFD4D4D4),
                        ),
                      ),
                      onPressed: () {
                        setState(() {
                          _directiveController.text = preset;
                          _activeDirective = preset;
                        });
                      },
                    );
                  },
                ),
              ),
            _ChatComposerBar(
              controller: _directiveController,
              isLoadingApps: _isLoadingApps,
              onAddPressed: _openInstalledAppsPicker,
              onSendPressed: _submitDirective,
            ),
          ],
        ),
      ),
    );
  }
}

class _PermissionsBanner extends StatelessWidget {
  final PermissionStatusState permissions;
  final VoidCallback onTapConfigure;

  const _PermissionsBanner({
    required this.permissions,
    required this.onTapConfigure,
  });

  @override
  Widget build(BuildContext context) {
    final List<String> missing = <String>[
      if (!permissions.overlayGranted) 'Overlay',
      if (!permissions.accessibilityGranted) 'Accessibility',
      if (!permissions.notificationGranted) 'Notifications',
    ];

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF1F1B12),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFF4B3B16)),
      ),
      child: Row(
        children: <Widget>[
          const Icon(
            Icons.warning_amber_rounded,
            color: Color(0xFFF59E0B),
            size: 20,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Missing permissions: ${missing.join(', ')}',
              style: const TextStyle(
                fontSize: 12.5,
                color: Color(0xFFFDE68A),
              ),
            ),
          ),
          TextButton(
            onPressed: onTapConfigure,
            style: TextButton.styleFrom(
              foregroundColor: const Color(0xFFF59E0B),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text(
              'Enable',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class _AssistantBubble extends StatelessWidget {
  final String text;

  const _AssistantBubble({required this.text});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Container(
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              color: const Color(0xFF1E1E1E),
              shape: BoxShape.circle,
              border: Border.all(color: const Color(0xFF2F2F2F)),
            ),
            child: const Icon(
              Icons.smart_toy_outlined,
              size: 16,
              color: Color(0xFF10A37F),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                text,
                style: const TextStyle(
                  fontSize: 14.5,
                  height: 1.45,
                  color: Color(0xFFE5E5E5),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _UserBubble extends StatelessWidget {
  final String text;

  const _UserBubble({required this.text});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16, left: 44),
      child: Align(
        alignment: Alignment.centerRight,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF262626),
            borderRadius: BorderRadius.circular(18),
          ),
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 14.5,
              height: 1.4,
              color: Color(0xFFF3F3F3),
            ),
          ),
        ),
      ),
    );
  }
}

class _GameActionCard extends StatelessWidget {
  final InstalledAppInfo app;
  final String directive;
  final bool isRunning;
  final bool isBusy;
  final VoidCallback onToggle;
  final VoidCallback onChangeGame;

  const _GameActionCard({
    required this.app,
    required this.directive,
    required this.isRunning,
    required this.isBusy,
    required this.onToggle,
    required this.onChangeGame,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1A1A1A),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: isRunning
              ? const Color(0xFFEF4444)
              : const Color(0xFF2E2E2E),
          width: 1.2,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              _AppIconBox(iconBytes: app.iconBytes, size: 48),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      app.appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16.5,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFFECECEC),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      app.packageName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Color(0xFF8E8E8E),
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: 'Change Target Game',
                onPressed: isRunning ? null : onChangeGame,
                icon: const Icon(
                  Icons.swap_horiz_rounded,
                  color: Color(0xFFA0A0A0),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xFF121212),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFF262626)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const Text(
                  'ACTIVE DIRECTIVE',
                  style: TextStyle(
                    fontSize: 10.5,
                    letterSpacing: 0.9,
                    fontWeight: FontWeight.w600,
                    color: Color(0xFF8E8E8E),
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  directive,
                  style: const TextStyle(
                    fontSize: 13.5,
                    height: 1.4,
                    color: Color(0xFFE0E0E0),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: <Widget>[
              Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                  color: isRunning
                      ? const Color(0xFFEF4444)
                      : const Color(0xFF6B7280),
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  isRunning
                      ? 'AUTOPILOT ACTIVE (HUD & VISION RUNNING)'
                      : 'STANDBY — READY TO LAUNCH',
                  style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                    color: isRunning
                        ? const Color(0xFFFCA5A5)
                        : const Color(0xFF9E9E9E),
                  ),
                ),
              ),
              ElevatedButton.icon(
                onPressed: isBusy ? null : onToggle,
                style: ElevatedButton.styleFrom(
                  backgroundColor: isRunning
                      ? const Color(0xFFEF4444)
                      : const Color(0xFF10A37F),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 18,
                    vertical: 12,
                  ),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                icon: isBusy
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : Icon(
                        isRunning
                            ? Icons.stop_rounded
                            : Icons.play_arrow_rounded,
                        size: 20,
                      ),
                label: Text(
                  isRunning ? 'STOP' : 'START',
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.6,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ChatComposerBar extends StatelessWidget {
  final TextEditingController controller;
  final bool isLoadingApps;
  final VoidCallback onAddPressed;
  final VoidCallback onSendPressed;

  const _ChatComposerBar({
    required this.controller,
    required this.isLoadingApps,
    required this.onAddPressed,
    required this.onSendPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      color: const Color(0xFF121212),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        decoration: BoxDecoration(
          color: const Color(0xFF212121),
          borderRadius: BorderRadius.circular(26),
          border: Border.all(color: const Color(0xFF303030)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: <Widget>[
            IconButton(
              tooltip: 'Select Installed Game',
              onPressed: isLoadingApps ? null : onAddPressed,
              icon: isLoadingApps
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Color(0xFFECECEC),
                      ),
                    )
                  : const Icon(
                      Icons.add_circle_outline_rounded,
                      color: Color(0xFFECECEC),
                      size: 26,
                    ),
            ),
            Expanded(
              child: TextField(
                controller: controller,
                minLines: 1,
                maxLines: 4,
                textInputAction: TextInputAction.send,
                onSubmitted: (_) => onSendPressed(),
                style: const TextStyle(
                  color: Color(0xFFECECEC),
                  fontSize: 14.5,
                ),
                decoration: const InputDecoration(
                  hintText: 'Enter single autopilot instruction...',
                  hintStyle: TextStyle(color: Color(0xFF7D7D7D)),
                  border: InputBorder.none,
                  contentPadding: EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 10,
                  ),
                ),
              ),
            ),
            IconButton(
              tooltip: 'Set Directive',
              onPressed: onSendPressed,
              icon: Container(
                width: 34,
                height: 34,
                decoration: const BoxDecoration(
                  color: Color(0xFFECECEC),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.arrow_upward_rounded,
                  color: Color(0xFF121212),
                  size: 19,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InstalledAppsSheet extends StatefulWidget {
  final List<InstalledAppInfo> apps;

  const _InstalledAppsSheet({required this.apps});

  @override
  State<_InstalledAppsSheet> createState() => _InstalledAppsSheetState();
}

class _InstalledAppsSheetState extends State<_InstalledAppsSheet> {
  final TextEditingController _searchController = TextEditingController();
  bool _gamesOnly = false;
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final List<InstalledAppInfo> filtered = widget.apps.where((app) {
      if (_gamesOnly && !app.isGame) return false;
      if (_query.isEmpty) return true;
      final String q = _query.toLowerCase();
      return app.appName.toLowerCase().contains(q) ||
          app.packageName.toLowerCase().contains(q);
    }).toList();

    return DraggableScrollableSheet(
      initialChildSize: 0.78,
      minChildSize: 0.5,
      maxChildSize: 0.94,
      expand: false,
      builder: (BuildContext context, ScrollController scrollController) {
        return Column(
          children: <Widget>[
            const SizedBox(height: 12),
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: const Color(0xFF3A3A3A),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
              child: Row(
                children: <Widget>[
                  const Expanded(
                    child: Text(
                      'Select Target Game or App',
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFFECECEC),
                      ),
                    ),
                  ),
                  FilterChip(
                    selected: _gamesOnly,
                    label: const Text('Games Only'),
                    selectedColor: const Color(0xFF10A37F),
                    backgroundColor: const Color(0xFF262626),
                    checkmarkColor: Colors.white,
                    labelStyle: TextStyle(
                      fontSize: 12,
                      color: _gamesOnly ? Colors.white : const Color(0xFFCCCCCC),
                    ),
                    onSelected: (bool value) {
                      setState(() => _gamesOnly = value);
                    },
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
              child: TextField(
                controller: _searchController,
                onChanged: (String value) {
                  setState(() => _query = value.trim());
                },
                style: const TextStyle(color: Color(0xFFECECEC)),
                decoration: InputDecoration(
                  hintText: 'Search installed apps or packages...',
                  hintStyle: const TextStyle(color: Color(0xFF7D7D7D)),
                  prefixIcon: const Icon(
                    Icons.search_rounded,
                    color: Color(0xFF8E8E8E),
                  ),
                  filled: true,
                  fillColor: const Color(0xFF242424),
                  contentPadding: const EdgeInsets.symmetric(vertical: 10),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            Expanded(
              child: filtered.isEmpty
                  ? const Center(
                      child: Text(
                        'No matching installed apps found.',
                        style: TextStyle(color: Color(0xFF8E8E8E)),
                      ),
                    )
                  : ListView.separated(
                      controller: scrollController,
                      itemCount: filtered.length,
                      separatorBuilder: (_, __) => const Divider(
                        height: 1,
                        color: Color(0xFF262626),
                      ),
                      itemBuilder: (BuildContext context, int index) {
                        final InstalledAppInfo app = filtered[index];
                        return ListTile(
                          leading: _AppIconBox(
                            iconBytes: app.iconBytes,
                            size: 42,
                          ),
                          title: Row(
                            children: <Widget>[
                              Expanded(
                                child: Text(
                                  app.appName,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: Color(0xFFECECEC),
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                              if (app.isGame)
                                Container(
                                  margin: const EdgeInsets.only(left: 8),
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 7,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFF12362C),
                                    borderRadius: BorderRadius.circular(6),
                                  ),
                                  child: const Text(
                                    'GAME',
                                    style: TextStyle(
                                      fontSize: 10,
                                      fontWeight: FontWeight.w700,
                                      color: Color(0xFF10A37F),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                          subtitle: Text(
                            app.packageName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 12,
                              color: Color(0xFF8E8E8E),
                            ),
                          ),
                          onTap: () => Navigator.of(context).pop(app),
                        );
                      },
                    ),
            ),
          ],
        );
      },
    );
  }
}

class _AppIconBox extends StatelessWidget {
  final Uint8List? iconBytes;
  final double size;

  const _AppIconBox({
    required this.iconBytes,
    required this.size,
  });

  @override
  Widget build(BuildContext context) {
    if (iconBytes != null && iconBytes!.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: Image.memory(
          iconBytes!,
          width: size,
          height: size,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => _fallbackIcon(),
        ),
      );
    }
    return _fallbackIcon();
  }

  Widget _fallbackIcon() {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: const Color(0xFF2B2B2B),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(
        Icons.sports_esports_rounded,
        size: size * 0.54,
        color: const Color(0xFF10A37F),
      ),
    );
  }
}

class _PermissionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool granted;
  final VoidCallback onGrant;

  const _PermissionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.granted,
    required this.onGrant,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF232323),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: granted
              ? const Color(0xFF14532D)
              : const Color(0xFF333333),
        ),
      ),
      child: Row(
        children: <Widget>[
          Icon(
            icon,
            color: granted
                ? const Color(0xFF10A37F)
                : const Color(0xFFBDBDBD),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: Color(0xFFECECEC),
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: const TextStyle(
                    fontSize: 11.5,
                    color: Color(0xFF9E9E9E),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          granted
              ? const Icon(
                  Icons.check_circle_rounded,
                  color: Color(0xFF10A37F),
                )
              : OutlinedButton(
                  onPressed: onGrant,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: const Color(0xFFECECEC),
                    side: const BorderSide(color: Color(0xFF4A4A4A)),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 6,
                    ),
                    minimumSize: Size.zero,
                  ),
                  child: const Text('Grant'),
                ),
        ],
      ),
    );
  }
}
