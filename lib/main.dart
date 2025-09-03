// The original content is temporarily commented out to allow generating a self-contained demo - feel free to uncomment later.

// import 'package:flutter/material.dart';
// import 'package:flutter/services.dart'; // Add this import for SystemChrome
// import 'package:flutter_map/flutter_map.dart';
// import 'package:latlong2/latlong.dart'; // For LatLng coordinates
// import 'package:webview_flutter/webview_flutter.dart';
//
// void main() {
//   runApp(const MyApp());
// }
//
// class MyApp extends StatelessWidget {
//   const MyApp({super.key});
//
//   @override
//   Widget build(BuildContext context) {
//     return MaterialApp(
//       title: 'Flutter OpenStreetMap Demo',
//       theme: ThemeData(
//         colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
//         useMaterial3: true,
//       ),
//       home: const MyHomePage(),
//     );
//   }
// }
//
// class MyHomePage extends StatefulWidget {
//   const MyHomePage({super.key});
//
//   @override
//   State<MyHomePage> createState() => _MyHomePageState();
// }
//
// class _MyHomePageState extends State<MyHomePage> with TickerProviderStateMixin {
//   late TabController tabController;
//
//   // OSM Tab variables
//   MapController mapController = MapController();
//   bool showWebView1 = false;
//   bool showWebView2 = false;
//   LatLng webViewPosition1 = LatLng(10.8231, 106.6297); // Ho Chi Minh City
//   LatLng webViewPosition2 = LatLng(
//     40.7677,
//     -73.9774,
//   ); // Central Park South, NYC
//   late WebViewController webViewController1;
//   late WebViewController webViewController2;
//   double? initialWebViewZoom;
//
//   // Agents Tab variables (renamed from Streams)
//   late WebViewController airbnbController;
//   late WebViewController lichessController;
//
//   // Console Tab variables
//   List<String> consoleMessages = [
//     'Welcome to DatomWorld Console',
//     'System initialized successfully',
//     'Ready for commands...',
//   ];
//
//   @override
//   void initState() {
//     super.initState();
//
//     // Set immersive mode
//     _setImmersiveMode();
//
//     // Initialize TabController
//     tabController = TabController(length: 3, vsync: this);
//
//     // Initialize OSM WebView controllers
//     webViewController1 = WebViewController()
//       ..setJavaScriptMode(JavaScriptMode.unrestricted)
//       ..setBackgroundColor(Colors.white)
//       ..loadRequest(Uri.parse("https://www.airbnb.com"));
//
//     webViewController2 = WebViewController()
//       ..setJavaScriptMode(JavaScriptMode.unrestricted)
//       ..setBackgroundColor(Colors.white)
//       ..loadRequest(Uri.parse("https://lichess.org"));
//
//     // Initialize Agents Tab controllers (renamed from Streams)
//     airbnbController = WebViewController()
//       ..setJavaScriptMode(JavaScriptMode.unrestricted)
//       ..setBackgroundColor(Colors.white)
//       ..loadRequest(Uri.parse("https://www.airbnb.com"));
//
//     lichessController = WebViewController()
//       ..setJavaScriptMode(JavaScriptMode.unrestricted)
//       ..setBackgroundColor(Colors.white)
//       ..loadRequest(Uri.parse("https://lichess.org"));
//
//     _addConsoleMessage('Immersive mode activated');
//   }
//
//   void _setImmersiveMode() {
//     SystemChrome.setEnabledSystemUIMode(
//       SystemUiMode.immersiveSticky,
//       overlays: [], // Hide all system UI
//     );
//
//     // Optional: Set system UI overlay style for when it appears temporarily
//     SystemChrome.setSystemUIOverlayStyle(
//       const SystemUiOverlayStyle(
//         statusBarColor: Colors.transparent,
//         statusBarIconBrightness: Brightness.light,
//         systemNavigationBarColor: Colors.transparent,
//         systemNavigationBarIconBrightness: Brightness.light,
//       ),
//     );
//   }
//
//   @override
//   void dispose() {
//     // Restore system UI when leaving the app
//     SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
//     tabController.dispose();
//     super.dispose();
//   }
//
//   // OSM Tab methods
//   void _toggleWebView1() {
//     setState(() {
//       showWebView1 = !showWebView1;
//       if (showWebView1) {
//         webViewPosition1 = LatLng(10.8231, 106.6297);
//         initialWebViewZoom ??= mapController.camera.zoom;
//       }
//     });
//   }
//
//   void _toggleWebView2() {
//     setState(() {
//       showWebView2 = !showWebView2;
//       if (showWebView2) {
//         webViewPosition2 = LatLng(40.7677, -73.9774);
//         initialWebViewZoom ??= mapController.camera.zoom;
//       }
//     });
//   }
//
//   void _closeWebView1() {
//     setState(() {
//       showWebView1 = false;
//     });
//   }
//
//   void _closeWebView2() {
//     setState(() {
//       showWebView2 = false;
//     });
//   }
//
//   // Console Tab methods
//   void _addConsoleMessage(String message) {
//     setState(() {
//       consoleMessages.add(
//         '${DateTime.now().toString().substring(11, 19)}: $message',
//       );
//     });
//   }
//
//   Widget _buildWebView({
//     required bool showWebView,
//     required LatLng webViewPosition,
//     required WebViewController webViewController,
//     required VoidCallback onClose,
//     required Color closeButtonColor,
//   }) {
//     if (!showWebView) return Container();
//
//     return Builder(
//       builder: (context) {
//         final screenSize = MediaQuery.of(context).size;
//         final currentZoom = mapController.camera.zoom;
//         final scaleFactor = currentZoom / (initialWebViewZoom ?? currentZoom);
//         final baseWidth = screenSize.width * 0.8;
//         final baseHeight = screenSize.height * 0.5;
//         final webViewWidth = baseWidth * scaleFactor;
//         final webViewHeight = baseHeight * scaleFactor;
//         final point = mapController.camera.latLngToScreenPoint(webViewPosition);
//
//         return Stack(
//           children: [
//             Positioned(
//               left: point.x - (webViewWidth / 2),
//               top: point.y - (webViewHeight / 2),
//               child: Material(
//                 elevation: 8,
//                 borderRadius: BorderRadius.circular(8),
//                 color: Colors.white,
//                 child: Container(
//                   width: webViewWidth,
//                   height: webViewHeight,
//                   decoration: BoxDecoration(
//                     borderRadius: BorderRadius.circular(8),
//                     color: Colors.white,
//                     boxShadow: [
//                       BoxShadow(
//                         color: Colors.black26,
//                         blurRadius: 10,
//                         offset: Offset(0, 4),
//                       ),
//                     ],
//                   ),
//                   child: ClipRRect(
//                     borderRadius: BorderRadius.circular(8),
//                     child: Container(
//                       color: Colors.white,
//                       child: WebViewWidget(controller: webViewController),
//                     ),
//                   ),
//                 ),
//               ),
//             ),
//             Positioned(
//               left: point.x + (webViewWidth / 2) - 10,
//               top: point.y - (webViewHeight / 2) - 10,
//               child: Material(
//                 color: closeButtonColor,
//                 borderRadius: BorderRadius.circular(20),
//                 elevation: 4,
//                 child: IconButton(
//                   icon: Icon(Icons.close, color: Colors.white, size: 20),
//                   onPressed: onClose,
//                   style: IconButton.styleFrom(
//                     backgroundColor: closeButtonColor,
//                     minimumSize: Size(40, 40),
//                     padding: EdgeInsets.zero,
//                   ),
//                 ),
//               ),
//             ),
//           ],
//         );
//       },
//     );
//   }
//
//   Widget _buildOSMTab() {
//     return Stack(
//       children: [
//         FlutterMap(
//           mapController: mapController,
//           options: MapOptions(
//             initialCenter: LatLng(25.7617, 16.6177),
//             initialZoom: 3.0,
//             minZoom: 1.0, // Allow zooming out to see the whole world
//             maxZoom: 18.0, // Standard max zoom for OSM
//             // Enable infinite scroll around the globe
//             cameraConstraint: CameraConstraint.unconstrained(),
//             // Alternative: use contain constraint for bounded scrolling
//             // cameraConstraint: CameraConstraint.contain(
//             //   bounds: LatLngBounds(
//             //     LatLng(-90, -180), // Southwest corner
//             //     LatLng(90, 180),   // Northeast corner
//             //   ),
//             // ),
//             onPositionChanged: (MapPosition position, bool hasGesture) {
//               if ((showWebView1 || showWebView2) && hasGesture) {
//                 setState(() {});
//               }
//             },
//             // Enable interaction callbacks for console logging
//             onTap: (tapPosition, point) {
//               _addConsoleMessage(
//                 'Map tapped at: ${point.latitude.toStringAsFixed(4)}, ${point.longitude.toStringAsFixed(4)}',
//               );
//             },
//             onLongPress: (tapPosition, point) {
//               _addConsoleMessage(
//                 'Map long-pressed at: ${point.latitude.toStringAsFixed(4)}, ${point.longitude.toStringAsFixed(4)}',
//               );
//             },
//           ),
//           children: [
//             TileLayer(
//               urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
//               userAgentPackageName: 'world.datom.datomworld',
//               // Enable infinite scroll by allowing negative coordinates
//               tileProvider: NetworkTileProvider(),
//               // Add error handling for tiles
//               errorTileCallback: (tile, error, stackTrace) {
//                 _addConsoleMessage('Tile loading error: ${error.toString()}');
//               },
//             ),
//             MarkerLayer(
//               markers: [
//                 Marker(
//                   point: LatLng(10.8231, 106.6297),
//                   width: 40,
//                   height: 40,
//                   child: GestureDetector(
//                     onTap: () {
//                       _toggleWebView1();
//                       _addConsoleMessage('Ho Chi Minh City marker tapped');
//                     },
//                     child: Icon(Icons.home, color: Colors.red, size: 40),
//                   ),
//                 ),
//                 Marker(
//                   point: LatLng(40.7677, -73.9774),
//                   width: 40,
//                   height: 40,
//                   child: GestureDetector(
//                     onTap: () {
//                       _toggleWebView2();
//                       _addConsoleMessage('Central Park South marker tapped');
//                     },
//                     child: Icon(
//                       Icons.location_city,
//                       color: Colors.blue,
//                       size: 40,
//                     ),
//                   ),
//                 ),
//                 // Add additional markers for infinite scroll demonstration
//                 Marker(
//                   point: LatLng(51.5074, -0.1278), // London
//                   width: 30,
//                   height: 30,
//                   child: GestureDetector(
//                     onTap: () {
//                       _addConsoleMessage('London marker tapped');
//                     },
//                     child: Icon(
//                       Icons.location_on,
//                       color: Colors.green,
//                       size: 30,
//                     ),
//                   ),
//                 ),
//                 Marker(
//                   point: LatLng(35.6762, 139.6503), // Tokyo
//                   width: 30,
//                   height: 30,
//                   child: GestureDetector(
//                     onTap: () {
//                       _addConsoleMessage('Tokyo marker tapped');
//                     },
//                     child: Icon(
//                       Icons.location_on,
//                       color: Colors.orange,
//                       size: 30,
//                     ),
//                   ),
//                 ),
//                 Marker(
//                   point: LatLng(-33.8688, 151.2093), // Sydney
//                   width: 30,
//                   height: 30,
//                   child: GestureDetector(
//                     onTap: () {
//                       _addConsoleMessage('Sydney marker tapped');
//                     },
//                     child: Icon(
//                       Icons.location_on,
//                       color: Colors.purple,
//                       size: 30,
//                     ),
//                   ),
//                 ),
//               ],
//             ),
//           ],
//         ),
//         _buildWebView(
//           showWebView: showWebView1,
//           webViewPosition: webViewPosition1,
//           webViewController: webViewController1,
//           onClose: _closeWebView1,
//           closeButtonColor: Colors.red,
//         ),
//         _buildWebView(
//           showWebView: showWebView2,
//           webViewPosition: webViewPosition2,
//           webViewController: webViewController2,
//           onClose: _closeWebView2,
//           closeButtonColor: Colors.blue,
//         ),
//         // Add immersive mode toggle button
//         Positioned(
//           top: 50,
//           right: 16,
//           child: FloatingActionButton(
//             mini: true,
//             onPressed: () {
//               _setImmersiveMode();
//               _addConsoleMessage('Immersive mode re-enabled');
//             },
//             child: Icon(Icons.fullscreen),
//             backgroundColor: Colors.deepPurple.withOpacity(0.8),
//             foregroundColor: Colors.white,
//           ),
//         ),
//         // Add zoom and location controls
//         Positioned(
//           top: 50,
//           left: 16,
//           child: Column(
//             children: [
//               FloatingActionButton(
//                 mini: true,
//                 heroTag: "zoom_in",
//                 onPressed: () {
//                   final currentZoom = mapController.camera.zoom;
//                   mapController.move(
//                     mapController.camera.center,
//                     currentZoom + 1,
//                   );
//                   _addConsoleMessage(
//                     'Zoomed in to level ${(currentZoom + 1).toStringAsFixed(1)}',
//                   );
//                 },
//                 child: Icon(Icons.zoom_in),
//                 backgroundColor: Colors.white.withOpacity(0.8),
//               ),
//               SizedBox(height: 8),
//               FloatingActionButton(
//                 mini: true,
//                 heroTag: "zoom_out",
//                 onPressed: () {
//                   final currentZoom = mapController.camera.zoom;
//                   mapController.move(
//                     mapController.camera.center,
//                     currentZoom - 1,
//                   );
//                   _addConsoleMessage(
//                     'Zoomed out to level ${(currentZoom - 1).toStringAsFixed(1)}',
//                   );
//                 },
//                 child: Icon(Icons.zoom_out),
//                 backgroundColor: Colors.white.withOpacity(0.8),
//               ),
//               SizedBox(height: 8),
//               FloatingActionButton(
//                 mini: true,
//                 heroTag: "center_world",
//                 onPressed: () {
//                   mapController.move(LatLng(0, 0), 2);
//                   _addConsoleMessage('Centered on world view');
//                 },
//                 child: Icon(Icons.public),
//                 backgroundColor: Colors.white.withOpacity(0.8),
//               ),
//             ],
//           ),
//         ),
//       ],
//     );
//   }
//
//   Widget _buildAgentsTab() {
//     // Renamed from _buildStreamsTab
//     return Column(
//       children: [
//         Container(
//           padding: EdgeInsets.all(16),
//           child: Text(
//             'Agents', // Changed from 'Streams'
//             style: Theme.of(context).textTheme.headlineMedium,
//           ),
//         ),
//         Expanded(
//           child: ListView(
//             children: [
//               Card(
//                 margin: EdgeInsets.all(8),
//                 child: ListTile(
//                   leading: Icon(Icons.home, color: Colors.red, size: 40),
//                   title: Text('AirBNB'),
//                   subtitle: Text('Accommodation booking platform'),
//                   trailing: Icon(Icons.arrow_forward_ios),
//                   onTap: () {
//                     _addConsoleMessage(
//                       'AirBNB agent opened',
//                     ); // Updated message
//                     Navigator.push(
//                       context,
//                       MaterialPageRoute(
//                         builder: (context) => Scaffold(
//                           appBar: AppBar(
//                             title: Text('AirBNB'),
//                             backgroundColor: Colors.red,
//                             foregroundColor: Colors.white,
//                           ),
//                           body: WebViewWidget(controller: airbnbController),
//                         ),
//                       ),
//                     );
//                   },
//                 ),
//               ),
//               Card(
//                 margin: EdgeInsets.all(8),
//                 child: ListTile(
//                   leading: Icon(Icons.games, color: Colors.blue, size: 40),
//                   title: Text('Li Chess'),
//                   subtitle: Text('Online chess platform'),
//                   trailing: Icon(Icons.arrow_forward_ios),
//                   onTap: () {
//                     _addConsoleMessage(
//                       'Li Chess agent opened',
//                     ); // Updated message
//                     Navigator.push(
//                       context,
//                       MaterialPageRoute(
//                         builder: (context) => Scaffold(
//                           appBar: AppBar(
//                             title: Text('Li Chess'),
//                             backgroundColor: Colors.blue,
//                             foregroundColor: Colors.white,
//                           ),
//                           body: WebViewWidget(controller: lichessController),
//                         ),
//                       ),
//                     );
//                   },
//                 ),
//               ),
//             ],
//           ),
//         ),
//       ],
//     );
//   }
//
//   Widget _buildConsoleTab() {
//     return Column(
//       children: [
//         Container(
//           padding: EdgeInsets.all(16),
//           child: Row(
//             mainAxisAlignment: MainAxisAlignment.spaceBetween,
//             children: [
//               Text(
//                 'Console',
//                 style: Theme.of(context).textTheme.headlineMedium,
//               ),
//               Row(
//                 children: [
//                   IconButton(
//                     icon: Icon(Icons.fullscreen),
//                     onPressed: () {
//                       _setImmersiveMode();
//                       _addConsoleMessage(
//                         'Immersive mode activated from Console',
//                       );
//                     },
//                     tooltip: 'Enable Immersive Mode',
//                   ),
//                   IconButton(
//                     icon: Icon(Icons.clear),
//                     onPressed: () {
//                       setState(() {
//                         consoleMessages.clear();
//                         consoleMessages.add('Console cleared');
//                       });
//                     },
//                     tooltip: 'Clear Console',
//                   ),
//                 ],
//               ),
//             ],
//           ),
//         ),
//         Expanded(
//           child: Container(
//             margin: EdgeInsets.all(8),
//             padding: EdgeInsets.all(8),
//             decoration: BoxDecoration(
//               color: Colors.black,
//               borderRadius: BorderRadius.circular(8),
//               border: Border.all(color: Colors.grey),
//             ),
//             child: ListView.builder(
//               itemCount: consoleMessages.length,
//               itemBuilder: (context, index) {
//                 return Padding(
//                   padding: EdgeInsets.symmetric(vertical: 2),
//                   child: Text(
//                     consoleMessages[index],
//                     style: TextStyle(
//                       color: Colors.green,
//                       fontFamily: 'monospace',
//                       fontSize: 12,
//                     ),
//                   ),
//                 );
//               },
//             ),
//           ),
//         ),
//       ],
//     );
//   }
//
//   @override
//   Widget build(BuildContext context) {
//     return Scaffold(
//       body: TabBarView(
//         controller: tabController,
//         children: [
//           _buildOSMTab(),
//           _buildAgentsTab(),
//           _buildConsoleTab(),
//         ], // Updated method name
//       ),
//       bottomNavigationBar: TabBar(
//         controller: tabController,
//         tabs: [
//           Tab(icon: Icon(Icons.map), text: 'OSM'),
//           Tab(
//             icon: Icon(Icons.smart_toy),
//             text: 'Agents',
//           ), // Changed icon and text
//           Tab(icon: Icon(Icons.terminal), text: 'Console'),
//         ],
//         labelColor: Colors.deepPurple,
//         unselectedLabelColor: Colors.grey,
//         indicatorColor: Colors.deepPurple,
//       ),
//     );
//   }
// }
//

import 'package:flutter/material.dart';
import 'package:datomworld/src/rust/api/simple.dart';
import 'package:datomworld/src/rust/frb_generated.dart';

Future<void> main() async {
  await RustLib.init();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('flutter_rust_bridge quickstart')),
        body: Center(
          child: Text(
            'Action: Call Rust `greet("Tom")`\nResult: `${greet(name: "Tom")}`',
          ),
        ),
      ),
    );
  }
}
