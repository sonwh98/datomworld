import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart'; // For LatLng coordinates
import 'package:webview_flutter/webview_flutter.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter OpenStreetMap Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true, // Optional: for Material 3 styling
      ),
      home: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  MapController mapController = MapController();
  bool showWebView = false;
  LatLng webViewPosition = LatLng(10.8231, 106.6297);
  late WebViewController webViewController;
  double? initialWebViewZoom; // Store the initial zoom when WebView first opens

  @override
  void initState() {
    super.initState();
    // Create WebViewController once and reuse it
    webViewController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..loadRequest(Uri.parse("https://www.airbnb.com"));
  }

  void _toggleWebView() {
    setState(() {
      showWebView = !showWebView;
      if (showWebView) {
        webViewPosition = LatLng(10.8231, 106.6297); // Set to marker position
        // Only set initial zoom if it hasn't been set before (first time opening)
        initialWebViewZoom ??= mapController.camera.zoom;
      }
    });
  }

  void _closeWebView() {
    setState(() {
      showWebView = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          FlutterMap(
            mapController: mapController,
            options: MapOptions(
              initialCenter: LatLng(
                10.8231,
                106.6297,
              ), // Ho Chi Minh City coordinates
              initialZoom: 12.0,
              onPositionChanged: (MapPosition position, bool hasGesture) {
                if (showWebView && hasGesture) {
                  setState(() {}); // Trigger rebuild to update WebView position
                }
              },
            ),
            children: [
              TileLayer(
                urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                userAgentPackageName: 'world.datom.datomworld',
              ),
              MarkerLayer(
                markers: [
                  Marker(
                    point: LatLng(10.8231, 106.6297), // Ho Chi Minh City center
                    width: 40,
                    height: 40,
                    child: GestureDetector(
                      onTap:
                          _toggleWebView,
                      child: Icon(Icons.home, color: Colors.red, size: 40),
                    ),
                  ),
                ],
              ),
            ],
          ),
          if (showWebView)
            Builder(
              builder: (context) {
                // Get screen size
                final screenSize = MediaQuery.of(context).size;

                // Calculate scale factor based on initial zoom level
                final currentZoom = mapController.camera.zoom;
                final scaleFactor =
                    currentZoom / (initialWebViewZoom ?? currentZoom);

                // Apply scale factor to WebView size
                final baseWidth = screenSize.width * 0.8;
                final baseHeight = screenSize.height * 0.5;
                final webViewWidth = baseWidth * scaleFactor;
                final webViewHeight = baseHeight * scaleFactor;

                // Convert LatLng to screen coordinates
                final point = mapController.camera.latLngToScreenPoint(
                  webViewPosition,
                );

                return Stack(
                  children: [
                    // WebView container
                    Positioned(
                      left:
                          point.x -
                          (webViewWidth / 2), // Center horizontally on marker
                      top:
                          point.y -
                          (webViewHeight / 2), // Center vertically on marker
                      child: Material(
                        elevation: 8,
                        borderRadius: BorderRadius.circular(8),
                        color: Colors.white,
                        child: Container(
                          width: webViewWidth,
                          height: webViewHeight,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(8),
                            color: Colors.white,
                            boxShadow: [
                              BoxShadow(
                                color: Colors.black26,
                                blurRadius: 10,
                                offset: Offset(0, 4),
                              ),
                            ],
                          ),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Container(
                              color: Colors.white,
                              child: WebViewWidget(
                                controller:
                                    webViewController, // Use the persistent controller
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                    // Close button positioned outside the WebView
                    Positioned(
                      left:
                          point.x +
                          (webViewWidth / 2) -
                          10, // Position outside right edge
                      top:
                          point.y -
                          (webViewHeight / 2) -
                          10, // Position above the WebView
                      child: Material(
                        color: Colors.red,
                        borderRadius: BorderRadius.circular(20),
                        elevation: 4,
                        child: IconButton(
                          icon: Icon(
                            Icons.close,
                            color: Colors.white,
                            size: 20,
                          ),
                          onPressed: _closeWebView,
                          style: IconButton.styleFrom(
                            backgroundColor: Colors.red,
                            minimumSize: Size(40, 40),
                            padding: EdgeInsets.zero,
                          ),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
        ],
      ),
    );
  }
}
