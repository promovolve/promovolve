<?php
/**
 * Tests for the data-section topic hint.
 *
 *   php integrations/wordpress/tests/topic-test.php
 *
 * WordPress is not installed here. This stubs the handful of core functions
 * the topic path touches, includes the plugin, and asserts on the result —
 * enough to pin the selection rules (which taxonomies, in what order, which
 * terms survive the cap) without a database or a web server. build-zip.sh
 * runs it, so a regression cannot be packaged.
 */

define( 'ABSPATH', __DIR__ );

// ── the fixture the stubs read ───────────────────────────────────────────
$GLOBALS['pv'] = array(
	'is_singular'  => true,
	'is_category'  => false,
	'is_tag'       => false,
	'is_tax'       => false,
	'post_type'    => 'post',
	'queried_id'   => 1,
	'queried_obj'  => null,
	'taxonomies'   => array(), // name => array('public'=>bool,'show_ui'=>bool)
	'terms'        => array(), // taxonomy => string[]
	'filters'      => array(),
	'meta'         => array(),
);

// ── WordPress surface ────────────────────────────────────────────────────
function add_action( $hook, $cb = null, $p = 10, $n = 1 ) {}
function add_shortcode( $tag, $cb ) {}
function plugin_basename( $file ) { return basename( $file ); }

function add_filter( $hook, $cb, $priority = 10, $args = 1 ) {
	$GLOBALS['pv']['filters'][ $hook ][] = $cb;
}
function apply_filters( $hook, $value ) {
	$rest = array_slice( func_get_args(), 2 );
	foreach ( $GLOBALS['pv']['filters'][ $hook ] ?? array() as $cb ) {
		$value = call_user_func_array( $cb, array_merge( array( $value ), $rest ) );
	}
	return $value;
}

function is_singular( $t = '' ) { return $GLOBALS['pv']['is_singular']; }
function is_category( $t = '' ) { return $GLOBALS['pv']['is_category']; }
function is_tag( $t = '' )      { return $GLOBALS['pv']['is_tag']; }
function is_tax( $t = '', $x = '' ) { return $GLOBALS['pv']['is_tax']; }
function get_queried_object_id() { return $GLOBALS['pv']['queried_id']; }
function get_queried_object()    { return $GLOBALS['pv']['queried_obj']; }
function get_post_type( $id = null ) { return $GLOBALS['pv']['post_type']; }

function get_object_taxonomies( $type, $output = 'names' ) {
	$out = array();
	foreach ( $GLOBALS['pv']['taxonomies'] as $name => $flags ) {
		$out[] = (object) array(
			'name'    => $name,
			'public'  => $flags['public'],
			'show_ui' => $flags['show_ui'],
		);
	}
	return $out;
}

function get_post_meta( $id, $key, $single = false ) {
	return $GLOBALS['pv']['meta'][ $key ] ?? '';
}

function get_the_terms( $id, $taxonomy ) {
	$names = $GLOBALS['pv']['terms'][ $taxonomy ] ?? null;
	if ( null === $names ) {
		return false; // WordPress returns false, or a WP_Error
	}
	return array_map( fn( $n ) => (object) array( 'name' => $n ), $names );
}

require __DIR__ . '/../promovolve/promovolve.php';

// ── harness ──────────────────────────────────────────────────────────────
$failures = 0;
function t( $label, $expected, $actual ) {
	global $failures;
	if ( $expected === $actual ) {
		echo "  ok   $label\n";
		return;
	}
	$failures++;
	echo "  FAIL $label\n       expected: " . var_export( $expected, true )
		. "\n       actual:   " . var_export( $actual, true ) . "\n";
}

/** Declare taxonomies (public+show_ui unless stated) and their terms. */
function fixture( array $taxonomies, array $terms, array $flags = array() ) {
	$GLOBALS['pv']['taxonomies'] = array();
	foreach ( $taxonomies as $name ) {
		$GLOBALS['pv']['taxonomies'][ $name ] = $flags[ $name ]
			?? array( 'public' => true, 'show_ui' => true );
	}
	$GLOBALS['pv']['terms']   = $terms;
	$GLOBALS['pv']['filters'] = array();
	$GLOBALS['pv']['meta']    = array();
}

echo "topic hint\n";

// The point of the change: a destination taxonomy is no longer invisible.
fixture(
	array( 'category', 'post_tag', 'destination' ),
	array(
		'category'    => array( 'Travel' ),
		'post_tag'    => array( 'budget' ),
		'destination' => array( 'Kyoto' ),
	)
);
t( 'reads a custom taxonomy alongside the built-ins', 'Travel, budget, Kyoto', promovolve_declared_topic() );

// The regression the interleave exists to prevent: before round-robin, eight
// tags consumed the whole budget and the destination never shipped.
fixture(
	array( 'category', 'post_tag', 'destination' ),
	array(
		'category'    => array( 'Travel' ),
		'post_tag'    => array( 't1', 't2', 't3', 't4', 't5', 't6', 't7', 't8' ),
		'destination' => array( 'Kamakura' ),
	)
);
$topic = promovolve_declared_topic();
t( 'a tag-heavy post still ships its destination', true, in_array( 'Kamakura', explode( ', ', $topic ), true ) );
t( 'the cap still holds', 8, count( explode( ', ', $topic ) ) );

// post_format is public and UI-visible, and still not a topic.
fixture(
	array( 'category', 'post_format' ),
	array( 'category' => array( 'Travel' ), 'post_format' => array( 'Aside' ) )
);
t( 'post_format is excluded', 'Travel', promovolve_declared_topic() );

// Internal plumbing is filtered structurally, not by name.
fixture(
	array( 'category', 'product_visibility' ),
	array( 'category' => array( 'Travel' ), 'product_visibility' => array( 'hidden' ) ),
	array( 'product_visibility' => array( 'public' => false, 'show_ui' => false ) )
);
t( 'non-public taxonomies are excluded', 'Travel', promovolve_declared_topic() );

// Stable order: category, post_tag, then the rest alphabetically — so the
// attribute value does not reshuffle between requests.
fixture(
	array( 'zone', 'post_tag', 'category', 'cuisine' ),
	array(
		'category' => array( 'c' ),
		'post_tag' => array( 'p' ),
		'cuisine'  => array( 'cu' ),
		'zone'     => array( 'z' ),
	)
);
t( 'order is category, post_tag, then alphabetical', 'c, p, cu, z', promovolve_declared_topic() );

// The escape hatch for a public-but-not-topical taxonomy.
fixture(
	array( 'category', 'sponsor' ),
	array( 'category' => array( 'Travel' ), 'sponsor' => array( 'Acme' ) )
);
add_filter( 'promovolve_topic_taxonomies', fn( $tax ) => array_values( array_diff( $tax, array( 'sponsor' ) ) ) );
t( 'the filter can drop a taxonomy', 'Travel', promovolve_declared_topic() );

// A taxonomy with no terms must not contribute an empty slot.
fixture(
	array( 'category', 'destination' ),
	array( 'category' => array( 'Travel' ) )
);
t( 'a term-less taxonomy contributes nothing', 'Travel', promovolve_declared_topic() );

// Unchanged behaviour below this line.
$GLOBALS['pv']['is_singular'] = false;
$GLOBALS['pv']['is_tax']      = true;
$GLOBALS['pv']['queried_obj'] = (object) array( 'name' => 'Kyoto' );
t( 'a taxonomy archive still reports its own term', 'Kyoto', promovolve_declared_topic() );

$GLOBALS['pv']['is_tax']      = false;
$GLOBALS['pv']['queried_obj'] = null;
t( 'front page, search and 404 say nothing', '', promovolve_declared_topic() );

echo "\ndeclared place\n";
// The topic block above finishes on the archive cases, which leave
// is_singular false.
$GLOBALS['pv']['is_singular'] = true;

// A place taxonomy is recognised by its slug and reported separately from
// the topic — the server treats the two hints differently.
fixture(
	array( 'category', 'destination' ),
	array( 'category' => array( 'Travel' ), 'destination' => array( 'Kyoto' ) )
);
t( 'reads a place taxonomy', 'Kyoto', promovolve_declared_place() );
t( 'the topic hint still carries everything', 'Travel, Kyoto', promovolve_declared_topic() );

// Topic-only taxonomies must not leak into the place hint, or the server
// gets "Sushi" offered as a location.
fixture(
	array( 'category', 'post_tag' ),
	array( 'category' => array( 'Food' ), 'post_tag' => array( 'Sushi' ) )
);
t( 'a post with no place taxonomy declares no place', '', promovolve_declared_place() );

// WordPress's own geodata convention, as a fallback.
fixture( array( 'category' ), array( 'category' => array( 'Travel' ) ) );
$GLOBALS['pv']['meta']['geo_address'] = ' Kamakura, Kanagawa ';
t( 'falls back to geo_address when no place taxonomy exists', 'Kamakura, Kanagawa', promovolve_declared_place() );

// A place taxonomy is better evidence than a free-text address, so the
// fallback must not fire when one is present.
fixture(
	array( 'category', 'location' ),
	array( 'category' => array( 'Travel' ), 'location' => array( 'Kyoto' ) )
);
$GLOBALS['pv']['meta']['geo_address'] = 'Somewhere Else';
t( 'a place taxonomy beats geo_address', 'Kyoto', promovolve_declared_place() );

fixture(
	array( 'category', 'destination' ),
	array( 'category' => array( 'Travel' ), 'destination' => array( 'Kyoto' ) )
);
add_filter( 'promovolve_place_taxonomies', fn( $slugs ) => array() );
t( 'the filter can disable place reading', '', promovolve_declared_place() );

// Archives are handled by the topic hint already; repeating the term as a
// place would double-count it.
fixture( array( 'category' ), array() );
$GLOBALS['pv']['is_singular'] = false;
t( 'archives declare no place', '', promovolve_declared_place() );
$GLOBALS['pv']['is_singular'] = true;

echo $failures ? "\n$failures failure(s)\n" : "\nall passed\n";
exit( $failures ? 1 : 0 );
