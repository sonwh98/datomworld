<?php
// Router for `php -S`: static files as-is, .php files and directory indexes
// dispatched, everything else to index.php (pretty permalinks).
$path = parse_url( $_SERVER['REQUEST_URI'], PHP_URL_PATH );
$file = $_SERVER['DOCUMENT_ROOT'] . $path;
if ( is_dir( $file ) ) {
	$file = rtrim( $file, '/' ) . '/index.php';
}
if ( is_file( $file ) ) {
	if ( preg_match( '/\.php$/', $file ) ) {
		$_SERVER['SCRIPT_FILENAME'] = $file;
		$_SERVER['SCRIPT_NAME']     = substr( $file, strlen( $_SERVER['DOCUMENT_ROOT'] ) );
		$_SERVER['PHP_SELF']        = $_SERVER['SCRIPT_NAME'];
		chdir( dirname( $file ) );
		require $file;
		return true;
	}
	return false; // php -S serves the static file
}
$_SERVER['SCRIPT_FILENAME'] = $_SERVER['DOCUMENT_ROOT'] . '/index.php';
$_SERVER['SCRIPT_NAME']     = '/index.php';
chdir( $_SERVER['DOCUMENT_ROOT'] );
require $_SERVER['DOCUMENT_ROOT'] . '/index.php';
