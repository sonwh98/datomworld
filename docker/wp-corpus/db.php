<?php
/**
 * dao.wordpress W0a recorder: a wpdb drop-in that OBSERVES and never alters.
 *
 * WordPress loads wp-content/db.php after class-wpdb.php and before it would
 * create its own $wpdb, so this file only replaces the instance. Every event
 * is appended as one JSON line to /corpus/<request-id>.jsonl. A string that is
 * not valid UTF-8 is written as {"hex": "..."} so the converter can emit the
 * {:fixture/bytes hex} marker.
 */

class Corpus_wpdb extends wpdb {
	private $corpus_seq = 0;
	private $corpus_file = null;
	private $corpus_pending_prepare = null;

	private function corpus_open() {
		if ( null !== $this->corpus_file ) {
			return;
		}
		$id = isset( $_SERVER['HTTP_X_CORPUS_REQUEST'] )
			? preg_replace( '/[^A-Za-z0-9._-]/', '_', $_SERVER['HTTP_X_CORPUS_REQUEST'] )
			: 'untagged-' . uniqid();
		$this->corpus_file = '/corpus/' . $id . '.jsonl';
		$this->corpus_emit( array(
			'kind'   => 'request',
			'id'     => $id,
			'method' => isset( $_SERVER['REQUEST_METHOD'] ) ? $_SERVER['REQUEST_METHOD'] : null,
			'uri'    => isset( $_SERVER['REQUEST_URI'] ) ? $_SERVER['REQUEST_URI'] : null,
			'server' => array_intersect_key( $_SERVER, array_flip( array(
				'SERVER_PROTOCOL', 'REQUEST_METHOD', 'REQUEST_URI', 'QUERY_STRING', 'SCRIPT_NAME',
				'SCRIPT_FILENAME', 'PHP_SELF', 'DOCUMENT_ROOT', 'HTTP_HOST', 'SERVER_NAME',
				'SERVER_PORT', 'REMOTE_ADDR', 'CONTENT_TYPE', 'CONTENT_LENGTH', 'HTTPS',
			) ) ),
		) );
	}

	private function corpus_val( $v ) {
		if ( is_string( $v ) && ! mb_check_encoding( $v, 'UTF-8' ) ) {
			return array( 'hex' => bin2hex( $v ) );
		}
		if ( is_array( $v ) || is_object( $v ) ) {
			$out = array();
			foreach ( (array) $v as $k => $x ) {
				$out[ $k ] = $this->corpus_val( $x );
			}
			return $out;
		}
		return $v;
	}

	private function corpus_emit( $event ) {
		$this->corpus_open();
		$event['seq'] = ++$this->corpus_seq;
		file_put_contents(
			$this->corpus_file,
			json_encode( $this->corpus_val( $event ), JSON_UNESCAPED_SLASHES | JSON_PARTIAL_OUTPUT_ON_ERROR ) . "\n",
			FILE_APPEND | LOCK_EX
		);
	}

	private function corpus_columns() {
		$cols = array();
		foreach ( (array) $this->col_info as $m ) {
			$cols[] = array(
				'name' => $m->name, 'table' => $m->table, 'type' => $m->type,
				'flags' => $m->flags, 'length' => $m->length,
				'decimals' => $m->decimals, 'charsetnr' => $m->charsetnr,
			);
		}
		return $cols;
	}

	public function prepare( $query, ...$args ) {
		$sql  = parent::prepare( $query, ...$args );
		$flat = ( 1 === count( $args ) && is_array( $args[0] ) ) ? $args[0] : $args;
		$this->corpus_pending_prepare = array(
			'template' => $query,
			'args'     => array_values( $flat ),
			'sql'      => is_string( $sql ) ? $sql : null,
		);
		return $sql;
	}

	public function query( $query ) {
		$prepare = $this->corpus_pending_prepare;
		$this->corpus_pending_prepare = null;
		$ret = parent::query( $query );

		$event = array(
			'kind'   => 'query',
			'sql'    => $this->last_query,
			'caller' => wp_debug_backtrace_summary( __CLASS__ ),
		);
		// A prepare is linked only when it produced exactly the executed SQL
		// (wpdb adds a placeholder escape to the produced string).
		if ( null !== $prepare && $prepare['sql'] === $this->last_query ) {
			unset( $prepare['sql'] );
			$event['prepare'] = $prepare;
		}
		$errno = ( $this->dbh instanceof mysqli ) ? mysqli_errno( $this->dbh ) : 0;
		if ( $errno ) {
			$event['error'] = array( 'errno' => $errno, 'text' => mysqli_error( $this->dbh ) );
		} else {
			$result = array(
				'rows-affected' => $this->rows_affected,
				'insert-id'     => $this->insert_id,
				'num-rows'      => $this->num_rows,
			);
			if ( $this->col_info ) {
				$result['columns'] = $this->corpus_columns();
				$rows = array();
				foreach ( (array) $this->last_result as $row ) {
					$rows[] = array_values( (array) $row );
				}
				$result['rows'] = $rows;
			}
			$event['result'] = $result;
		}
		$this->corpus_emit( $event );
		return $ret;
	}

	public function _real_escape( $data ) {
		$out = parent::_real_escape( $data );
		if ( is_string( $data ) ) {
			$this->corpus_emit( array( 'kind' => 'escape', 'in' => $data, 'out' => $out ) );
		}
		return $out;
	}

	public function db_connect( $allow_bail = true ) {
		$ret = parent::db_connect( $allow_bail );
		$this->corpus_emit( array( 'kind' => 'session', 'op' => 'db_connect', 'ok' => (bool) $ret ) );
		return $ret;
	}

	public function set_charset( $dbh, $charset = null, $collate = null ) {
		$ret = parent::set_charset( $dbh, $charset, $collate );
		$this->corpus_emit( array( 'kind' => 'session', 'op' => 'set_charset', 'charset' => $this->charset, 'collate' => $this->collate ) );
		return $ret;
	}

	public function set_sql_mode( $modes = array() ) {
		parent::set_sql_mode( $modes );
		$this->corpus_emit( array( 'kind' => 'session', 'op' => 'set_sql_mode', 'modes' => $modes ) );
	}
}

$wpdb = new Corpus_wpdb( DB_USER, DB_PASSWORD, DB_NAME, DB_HOST );
