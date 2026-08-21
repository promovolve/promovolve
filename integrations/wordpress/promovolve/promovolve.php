<?php
/**
 * Plugin Name:       Promovolve Publisher
 * Description:       Connects this site to a Promovolve ad server: prints the ad tag, serves the site-verification file, and places ad slots via editor block or shortcode.
 * Version:           0.4.0
 * Requires at least: 6.0
 * Requires PHP:      7.4
 * Author:            Promovolve
 * License:           Apache-2.0
 * License URI:       https://www.apache.org/licenses/LICENSE-2.0
 * Text Domain:       promovolve
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

const PROMOVOLVE_OPTION  = 'promovolve_settings';
const PROMOVOLVE_VERSION = '0.4.0';

/**
 * Settings with defaults applied.
 *
 * @return array{site_id:string,api_base:string,script_url:string,verification_token:string,auto_slot_enabled:bool,auto_slot_id:string,auto_slot_w:int,auto_slot_h:int}
 */
function promovolve_settings() {
	$defaults = array(
		'site_id'            => '',
		'api_base'           => '',
		'script_url'         => '',
		'verification_token' => '',
		'auto_slot_enabled'  => false,
		'auto_slot_id'       => 'article-footer',
		'auto_slot_scope'    => 'site',
		'auto_slot_w'        => 728,
		'auto_slot_h'        => 90,
	);
	$saved = get_option( PROMOVOLVE_OPTION, array() );
	return array_merge( $defaults, is_array( $saved ) ? $saved : array() );
}

/**
 * The host Promovolve treats as this site's identity: home host, lowercased,
 * one leading "www." stripped (www/apex are the same site; every other
 * subdomain is a separate site).
 */
function promovolve_canonical_host() {
	$host = strtolower( (string) wp_parse_url( home_url( '/' ), PHP_URL_HOST ) );
	return preg_replace( '/^www\./', '', $host );
}

/* -------------------------------------------------------------------------
 * Ad tag
 * ---------------------------------------------------------------------- */

add_action( 'wp_enqueue_scripts', function () {
	$s = promovolve_settings();
	if ( '' === $s['site_id'] || '' === $s['api_base'] || '' === $s['script_url'] ) {
		return;
	}
	// Version null: no ?ver= cache-buster — the stable loader alias carries
	// its own short max-age and must stay byte-addressable by URL.
	wp_enqueue_script( 'promovolve-ad', $s['script_url'], array(), null, false );
} );

add_filter( 'script_loader_tag', function ( $tag, $handle ) {
	if ( 'promovolve-ad' !== $handle ) {
		return $tag;
	}
	$s = promovolve_settings();
	// The loader reads document.currentScript, so the attributes must live on
	// the real <script src> tag itself.
	$section = promovolve_declared_topic();
	$place   = promovolve_declared_place();
	$attrs   = sprintf(
		' data-pub="%s" data-api="%s"%s%s src=',
		esc_attr( $s['site_id'] ),
		esc_attr( $s['api_base'] ),
		'' === $section ? '' : sprintf( ' data-section="%s"', esc_attr( $section ) ),
		'' === $place ? '' : sprintf( ' data-place="%s"', esc_attr( $place ) )
	);
	return str_replace( ' src=', $attrs, $tag );
}, 10, 2 );

/**
 * The topic THIS page is about, according to WordPress itself.
 *
 * The ad server classifies pages by sending rendered text to an LLM. That
 * works, but WordPress already knows the answer for its own content: a post's
 * categories and tags are assigned facts, not inferences, and an archive knows
 * exactly which term it lists. Handing that over is the one classification
 * advantage this plugin has over a hand-pasted tag.
 *
 * It is a HINT, never an answer. The server treats it as an unverified,
 * interested claim — a publisher earns more from some categories than others —
 * and the page's own content stays the authority. So a blank or wrong value
 * costs nothing, which is why every branch here can safely return ''.
 *
 * Archives are the case that gains most: their rendered text is a blend of
 * excerpts from unrelated posts, so the term name is far better evidence than
 * anything the DOM offers.
 *
 * @return string Comma-separated topic list, or '' when WordPress has nothing
 *                confident to say (front page, 404, search results).
 */
function promovolve_declared_topic() {
	// Singular: every topical taxonomy the post type has, not just the two
	// built-in ones. A travel site keeps its destinations in a `destination`
	// taxonomy and its recipes in `cuisine`; reading only category and
	// post_tag threw away the most specific thing WordPress knew.
	if ( is_singular() ) {
		$post_id     = get_queried_object_id();
		$by_taxonomy = array();
		foreach ( promovolve_topic_taxonomies( $post_id ) as $taxonomy ) {
			$terms = get_the_terms( $post_id, $taxonomy );
			if ( ! is_array( $terms ) ) {
				continue; // no terms, or a WP_Error — either way, nothing to say
			}
			$names = array();
			foreach ( $terms as $term ) {
				if ( isset( $term->name ) ) {
					$names[] = $term->name;
				}
			}
			if ( ! empty( $names ) ) {
				$by_taxonomy[] = $names;
			}
		}
		return promovolve_join_topic( promovolve_interleave( $by_taxonomy ) );
	}

	// Category / tag / custom-taxonomy archive: the term IS the topic.
	if ( is_category() || is_tag() || is_tax() ) {
		$term = get_queried_object();
		return isset( $term->name ) ? promovolve_join_topic( array( $term->name ) ) : '';
	}

	// Everything else — front page, search, 404, date archives — has no
	// single honest topic. Say nothing rather than guess; the server then
	// classifies from content exactly as it does for a hand-embedded tag.
	return '';
}

/**
 * Taxonomy slugs that usually hold a PLACE rather than a topic.
 *
 * Matched on the slug, not the label, so a site can use its own wording
 * ("Reiseziel", "行き先") as long as the taxonomy is registered under one of
 * these. The filter below is the escape hatch for anything else.
 */
const PROMOVOLVE_PLACE_TAXONOMIES = array(
	'destination',
	'destinations',
	'location',
	'locations',
	'place',
	'places',
	'city',
	'cities',
	'region',
	'regions',
	'country',
	'countries',
	'area',
	'areas',
	'prefecture',
	'prefectures',
	'state',
	'states',
	'province',
	'provinces',
);

/**
 * Where THIS page is about, according to WordPress.
 *
 * Note what this is and is not. It is a property of the POST — where the
 * article is set — and never anything about the person reading it. That is
 * what makes it safe to print into markup a page cache will store and replay
 * to everyone: the answer does not vary by reader. A value derived from a
 * visitor's IP would be captured by the same cache and served to the world,
 * which is why this plugin does not and will not carry one.
 *
 * Names, not codes. The plugin has no gazetteer and should not grow one — it
 * sends "Kyoto" or "京都" and the server resolves it against a vocabulary it
 * controls. A publisher-supplied ISO code would be an unverified value
 * dressed as an authoritative one.
 *
 * @return string Comma-separated place names, or '' when nothing is declared.
 */
function promovolve_declared_place() {
	if ( ! is_singular() ) {
		// On a place archive the term IS the place, and promovolve_declared_topic
		// already sends it as the topic; repeating it here adds nothing.
		return '';
	}

	$post_id = get_queried_object_id();

	/**
	 * Filter the taxonomy slugs treated as places.
	 *
	 * @param string[] $slugs   Taxonomy slugs.
	 * @param int      $post_id Post being rendered.
	 */
	$slugs = apply_filters( 'promovolve_place_taxonomies', PROMOVOLVE_PLACE_TAXONOMIES, $post_id );

	$by_taxonomy = array();
	foreach ( promovolve_topic_taxonomies( $post_id ) as $taxonomy ) {
		if ( ! in_array( $taxonomy, $slugs, true ) ) {
			continue;
		}
		$terms = get_the_terms( $post_id, $taxonomy );
		if ( ! is_array( $terms ) ) {
			continue;
		}
		$names = array();
		foreach ( $terms as $term ) {
			if ( isset( $term->name ) ) {
				$names[] = $term->name;
			}
		}
		if ( ! empty( $names ) ) {
			$by_taxonomy[] = $names;
		}
	}

	// WordPress's own geodata convention, as a fallback when no place
	// taxonomy exists. Free text, which is exactly what the server wants.
	// geo_latitude / geo_longitude are deliberately NOT read: coordinates
	// are out of scope, and reverse-geocoding them belongs on the server if
	// it ever happens at all.
	if ( empty( $by_taxonomy ) ) {
		$address = get_post_meta( $post_id, 'geo_address', true );
		if ( is_string( $address ) && '' !== trim( $address ) ) {
			$by_taxonomy[] = array( trim( $address ) );
		}
	}

	return promovolve_join_topic( promovolve_interleave( $by_taxonomy ) );
}

/**
 * Taxonomies whose terms are never a topic, however public they look.
 *
 * post_format yields "Aside" / "Gallery" — a presentation choice, not a
 * subject. Everything else is filtered structurally below.
 */
const PROMOVOLVE_TOPIC_TAXONOMY_DENY = array( 'post_format' );

/**
 * The taxonomies this post's type keeps topical terms in.
 *
 * Public and UI-visible is the structural test: the taxonomies that fail it
 * are plumbing (product_visibility, wp_theme, nav menus) and never describe
 * content. `category` and `post_tag` lead because their meaning is fixed on
 * every WordPress site; the rest are sorted so the attribute value is stable
 * across requests — a hint that reshuffled per request would vary the markup
 * for no gain.
 *
 * Widening this does admit noise: a public taxonomy named "Sponsor" or
 * "Author" is not a topic. That costs little — the server is told the whole
 * hint is an interested claim and to ignore it when the content disagrees —
 * and the filter below is the escape hatch for a site that wants one gone.
 *
 * @param int $post_id Post being rendered.
 * @return string[] Taxonomy names, in the order they should be read.
 */
function promovolve_topic_taxonomies( $post_id ) {
	$found = array();
	foreach ( get_object_taxonomies( get_post_type( $post_id ), 'objects' ) as $taxonomy ) {
		if ( empty( $taxonomy->public ) || empty( $taxonomy->show_ui ) ) {
			continue;
		}
		if ( in_array( $taxonomy->name, PROMOVOLVE_TOPIC_TAXONOMY_DENY, true ) ) {
			continue;
		}
		$found[] = $taxonomy->name;
	}

	$leading = array_values( array_intersect( array( 'category', 'post_tag' ), $found ) );
	$rest    = array_diff( $found, $leading );
	sort( $rest );

	/**
	 * Filter the taxonomies read for the data-section hint.
	 *
	 * @param string[] $taxonomies Taxonomy names, in read order.
	 * @param int      $post_id    Post being rendered.
	 */
	return apply_filters( 'promovolve_topic_taxonomies', array_merge( $leading, $rest ), $post_id );
}

/**
 * Round-robin per-taxonomy term lists into one.
 *
 * Concatenating them let a single taxonomy eat the whole budget: a post with
 * eight tags pushed its `destination` terms past the cap, so the one taxonomy
 * carrying the page's location never reached the server at all. Taking one
 * term from each list in turn means every taxonomy contributes before any
 * contributes twice, which is what makes the cap survivable.
 *
 * @param string[][] $lists Term names grouped by taxonomy.
 * @return string[]
 */
function promovolve_interleave( $lists ) {
	$deepest = 0;
	foreach ( $lists as $list ) {
		$deepest = max( $deepest, count( $list ) );
	}
	$out = array();
	for ( $i = 0; $i < $deepest; $i++ ) {
		foreach ( $lists as $list ) {
			if ( isset( $list[ $i ] ) ) {
				$out[] = $list[ $i ];
			}
		}
	}
	return $out;
}

/**
 * Join topic names for the data-section attribute.
 *
 * Bounded here as well as server-side: the server caps and flattens the value
 * because it must never trust a client, but a post with forty tags would
 * otherwise ship a long attribute on every page for a hint the server will
 * truncate anyway.
 *
 * Eight rather than five since the hint now spans several taxonomies — enough
 * for a category, a couple of tags and a destination to travel together, and
 * still far inside the server's own 200-character bound. The interleave above
 * is what decides WHICH eight.
 *
 * @param string[] $names Term names.
 * @return string
 */
function promovolve_join_topic( $names ) {
	$names = array_values( array_unique( array_filter( array_map( 'trim', $names ) ) ) );
	if ( empty( $names ) ) {
		return '';
	}
	return implode( ', ', array_slice( $names, 0, 8 ) );
}

/* -------------------------------------------------------------------------
 * Site verification: /.well-known/promovolve.txt
 * ---------------------------------------------------------------------- */

add_action( 'init', function () {
	if ( empty( $_SERVER['REQUEST_URI'] ) ) {
		return;
	}
	$path = wp_parse_url( wp_unslash( $_SERVER['REQUEST_URI'] ), PHP_URL_PATH );
	if ( '/.well-known/promovolve.txt' !== $path ) {
		return;
	}
	$s = promovolve_settings();
	if ( '' === $s['verification_token'] ) {
		return; // No token configured — let WordPress 404 normally.
	}
	nocache_headers();
	header( 'Content-Type: text/plain; charset=utf-8' );
	echo 'promovolve-site-verification=' . $s['verification_token']; // phpcs:ignore WordPress.Security.EscapeOutput -- token is sanitized to [A-Za-z0-9-] on save, text/plain response.
	exit;
}, 1 );

/* -------------------------------------------------------------------------
 * Ad slots
 * ---------------------------------------------------------------------- */

/**
 * Slot container markup. The loader discovers these at DOMContentLoaded and
 * replaces the children on fill, so the div may stay empty.
 */
function promovolve_slot_html( $slot_id, $w, $h, $class = '' ) {
	$w = (int) $w;
	$h = (int) $h;
	if ( '' === $slot_id || $w < 1 || $h < 1 || ! promovolve_slot_claim( $slot_id ) ) {
		return '';
	}
	$class_attr = '' !== $class ? sprintf( ' class="%s"', esc_attr( $class ) ) : '';
	return sprintf(
		'<div%s style="%s" data-promovolve-slot="%s" data-w="%d" data-h="%d"></div>',
		$class_attr,
		esc_attr( promovolve_slot_style( $w, $h ) ),
		esc_attr( $slot_id ),
		$w,
		$h
	);
}

/**
 * First-come claim on a slot ID for this request.
 *
 * The loader fills only the FIRST element with a given slot ID per page, so
 * every later one is dead markup that still reserves its box — an empty hole in
 * the layout that can never fill. Archives are where this bites without anyone
 * making a mistake: a theme that renders full post content repeats every
 * in-content slot once per listed post.
 *
 * Suppressing the repeats loses nothing (they could never have filled) and is
 * the same rule the loader already applies, moved earlier.
 *
 * @return bool True if the caller may render this slot.
 */
function promovolve_slot_claim( $slot_id ) {
	// One HTML document is one loader pass — that assumption is what makes the
	// claim safe. It does not hold for responses that carry several renders at
	// once (the REST API returning content.rendered for a list of posts, feeds,
	// editor previews), so leave those untouched.
	if ( is_admin() || wp_doing_ajax() || is_feed() || ( defined( 'REST_REQUEST' ) && REST_REQUEST ) ) {
		return true;
	}
	static $claimed = array();
	if ( isset( $claimed[ $slot_id ] ) ) {
		return false;
	}
	$claimed[ $slot_id ] = true;
	return true;
}

/**
 * Inline sizing for a slot container — the container is authoritative for the
 * rendered ad's size (the banner fills 100% of it), and a bare div stretches to
 * the theme's full content column: a 300x250 rendered ~700px wide. Same
 * contract as the reference publisher CSS: fill the column UP TO the declared
 * size, preserve the aspect ratio, centered.
 */
function promovolve_slot_style( $w, $h ) {
	return sprintf( 'display:block;width:100%%;max-width:%dpx;aspect-ratio:%d/%d;margin:16px auto;', (int) $w, (int) $w, (int) $h );
}

/**
 * The configured size is part of a slot's identity: a 728x90 strip and a
 * 300x250 rectangle are different inventory even at the same page position, and
 * separate IDs keep floor learning and ad pools per shape. Changing the size
 * therefore starts a fresh slot — the ad server enrolls it on its first
 * request; the old size's dashboard rows stay behind as history.
 *
 * Shared by the automatic slot and the editor block. The shortcode deliberately
 * does NOT apply it: shortcode IDs are written by hand and already live as
 * inventory rows, so rewriting them would orphan existing slots.
 */
function promovolve_sized_slot_id( $base, $w, $h ) {
	return $base . '_' . (int) $w . 'x' . (int) $h;
}

add_shortcode( 'promovolve_slot', function ( $atts ) {
	$a = shortcode_atts(
		array(
			'id'    => '',
			'w'     => 0,
			'h'     => 0,
			'class' => '',
		),
		$atts,
		'promovolve_slot'
	);
	return promovolve_slot_html( $a['id'], $a['w'], $a['h'], $a['class'] );
} );

/**
 * Identity suffix that splits one placement into several dashboard slots.
 *
 * Every distinct slot ID becomes a permanent inventory row on the Promovolve
 * dashboard and a separate ad candidate pool, so the scope is a real
 * granularity/cardinality trade-off:
 * - 'site':     one shared slot everywhere (least granular; ad pools and the
 *               dashboard category label blend all posts).
 * - 'category': one slot per WordPress category (bounded, topically coherent
 *               pools; recommended for blogs).
 * - 'post':     one slot per post (exact page attribution; a row and a cold
 *               ad pool per post — avoid on large sites).
 *
 * Shared by the automatic slot and the editor block. The block needs it for the
 * same reason the automatic slot does: placed in a Site Editor template it is
 * ONE placement rendering on every post, so without a scope it could only ever
 * be a single shared slot.
 */
function promovolve_slot_scope_suffix( $scope ) {
	if ( 'category' !== $scope && 'post' !== $scope ) {
		return '';
	}
	// Only a singular view has an unambiguous "current post": inside an archive
	// the global post is whichever one the loop last touched, so a per-post or
	// per-category suffix there would be arbitrary and would mint junk
	// inventory rows. Fall back to the shared ID.
	if ( ! is_singular() ) {
		return '';
	}
	if ( 'post' === $scope ) {
		return '-post-' . get_the_ID();
	}
	$cats = get_the_category();
	if ( empty( $cats ) ) {
		return ''; // No category (e.g. static pages): fall back to the shared slot.
	}
	$slug = strtolower( $cats[0]->slug );
	// Non-Latin category slugs are percent-encoded by WordPress and would
	// sanitize to opaque hex — key those by term ID instead.
	return '-' . ( preg_match( '/^[a-z0-9-]+$/', $slug ) ? $slug : 'cat' . $cats[0]->term_id );
}

/**
 * Effective slot ID for the automatic slot on the current post.
 */
function promovolve_auto_slot_id( $s ) {
	return promovolve_sized_slot_id( $s['auto_slot_id'], $s['auto_slot_w'], $s['auto_slot_h'] )
		. promovolve_slot_scope_suffix( $s['auto_slot_scope'] );
}

add_filter( 'the_content', function ( $content ) {
	$s = promovolve_settings();
	if ( ! $s['auto_slot_enabled'] || ! is_singular() || ! in_the_loop() || ! is_main_query() ) {
		return $content;
	}
	// Singular only: archives rendering full content would repeat the slot ID,
	// and the loader fills only the first match per page.
	return $content . promovolve_slot_html( promovolve_auto_slot_id( $s ), $s['auto_slot_w'], $s['auto_slot_h'] );
} );

/* -------------------------------------------------------------------------
 * Editor block
 * ---------------------------------------------------------------------- */

/**
 * The block is dynamic (render_callback, save() returns null): the front-end
 * markup lives in PHP alongside the shortcode's, so the two can never drift and
 * changing the container later does not invalidate already-saved posts.
 *
 * Registering it as a normal block means it is placeable everywhere the editor
 * reaches — post/page content, Site Editor templates and template parts, and
 * block widget areas — which is what the after-content automatic slot cannot do.
 */
add_action( 'init', function () {
	$block_dir = __DIR__ . '/blocks/slot';
	if ( ! function_exists( 'register_block_type' ) || ! file_exists( $block_dir . '/block.json' ) ) {
		return;
	}

	// No build step, so there is no generated *.asset.php to read dependencies
	// from — register the handle explicitly and let block.json reference it.
	wp_register_script(
		'promovolve-slot-block',
		plugins_url( 'blocks/slot/editor.js', __FILE__ ),
		array( 'wp-blocks', 'wp-element', 'wp-block-editor', 'wp-components', 'wp-data', 'wp-i18n' ),
		PROMOVOLVE_VERSION,
		true
	);

	$s = promovolve_settings();
	wp_add_inline_script(
		'promovolve-slot-block',
		'window.promovolveBlock = ' . wp_json_encode(
			array(
				// Slots on an unconfigured site are inert markup — say so in the
				// editor rather than letting it look like a broken ad server.
				'configured'  => ( '' !== $s['site_id'] && '' !== $s['api_base'] && '' !== $s['script_url'] ),
				'settingsUrl' => admin_url( 'options-general.php?page=promovolve' ),
			)
		) . ';',
		'before'
	);

	register_block_type( $block_dir, array( 'render_callback' => 'promovolve_render_slot_block' ) );
} );

/**
 * @param array $attributes Block attributes.
 * @return string Slot container markup, or '' when the block is not usable.
 */
function promovolve_render_slot_block( $attributes ) {
	$attributes = is_array( $attributes ) ? $attributes : array();
	// Match the editor's sanitizer: slot IDs are inventory keys on the
	// dashboard, so keep them to a conservative alphabet on both sides.
	$base = preg_replace( '/[^a-z0-9-]/', '', strtolower( trim( (string) ( $attributes['slotId'] ?? '' ) ) ) );
	$w    = (int) ( $attributes['w'] ?? 0 );
	$h    = (int) ( $attributes['h'] ?? 0 );
	if ( '' === $base || $w < 1 || $h < 1 ) {
		return '';
	}
	$scope = (string) ( $attributes['scope'] ?? 'site' );
	if ( ! in_array( $scope, array( 'site', 'category', 'post' ), true ) ) {
		$scope = 'site';
	}

	$slot_id = promovolve_sized_slot_id( $base, $w, $h ) . promovolve_slot_scope_suffix( $scope );
	if ( ! promovolve_slot_claim( $slot_id ) ) {
		return '';
	}

	// get_block_wrapper_attributes() carries the margin block support and the
	// editor's generated classes; our sizing goes first so a publisher's
	// explicit margin wins over the default. Alignment is deliberately NOT
	// supported: the slot is capped at its declared width, so "wide"/"full"
	// could not take effect and a float would fight width:100%.
	$wrapper = get_block_wrapper_attributes( array( 'style' => promovolve_slot_style( $w, $h ) ) );
	return sprintf(
		'<div %s data-promovolve-slot="%s" data-w="%d" data-h="%d"></div>',
		$wrapper, // phpcs:ignore WordPress.Security.EscapeOutput -- core-escaped attribute string.
		esc_attr( $slot_id ),
		$w,
		$h
	);
}

/* -------------------------------------------------------------------------
 * Verification-file probe
 * ---------------------------------------------------------------------- */

const PROMOVOLVE_WELLKNOWN_TRANSIENT = 'promovolve_wellknown_status';
const PROMOVOLVE_VERIFIED_TRANSIENT  = 'promovolve_verification_status';

/**
 * Ask the ad server whether this site is verified.
 *
 * The serve endpoint already answers this: its host gate replies 403
 * (BatchHostNotVerified) for an unverified site or a host mismatch, and that
 * check runs BEFORE the auction, so an unverified probe costs the server
 * nothing. Sending an EMPTY impression list keeps the verified case just as
 * cheap — the request passes the gate, finds no slots to fill, and returns
 * `seatbid: []`, so it can neither reserve budget nor enroll a slot id. (It
 * does count as one request arrival, which is why the answer is cached and
 * only fetched when an admin opens this page.)
 *
 * Worth asking directly rather than inferring from the verification file:
 * verification is one-time and persisted server-side, so a site stays verified
 * long after the file stops being served — which is exactly what a
 * remove-and-reinstall of this plugin produces.
 *
 * @return string 'verified' | 'unverified' | 'unknown'
 */
function promovolve_verification_status( $s ) {
	if ( '' === $s['site_id'] || '' === $s['api_base'] ) {
		return 'unknown'; // Nothing to ask with.
	}
	$cached = get_transient( PROMOVOLVE_VERIFIED_TRANSIENT );
	if ( is_string( $cached ) && '' !== $cached ) {
		return $cached;
	}

	$response = wp_remote_post(
		untrailingslashit( $s['api_base'] ) . '/v1/serve/batch',
		array(
			'timeout' => 5,
			'headers' => array( 'Content-Type' => 'application/json' ),
			'body'    => wp_json_encode( array(
				'pub' => $s['site_id'],
				'url' => home_url( '/' ),
				'imp' => array(),
			) ),
		)
	);

	if ( is_wp_error( $response ) ) {
		$state = 'unknown';
	} else {
		$code = (int) wp_remote_retrieve_response_code( $response );
		if ( 200 === $code || 204 === $code ) {
			// 204 = operator-suspended: the host gate passed, so the site IS
			// verified. Suspension is a separate condition with its own
			// dashboard surface; do not report it as a verification failure.
			$state = 'verified';
		} elseif ( 403 === $code ) {
			$state = 'unverified';
		} else {
			$state = 'unknown';
		}
	}

	// Short cache: this decides whether the token field appears at all, so a
	// publisher who has just verified should see it disappear promptly.
	set_transient( PROMOVOLVE_VERIFIED_TRANSIENT, $state, 5 * MINUTE_IN_SECONDS );
	return $state;
}

/**
 * What does the world ACTUALLY see at /.well-known/promovolve.txt?
 *
 * The settings field alone cannot answer that. Uninstalling the plugin
 * deletes its option (uninstall.php), so a remove-and-reinstall leaves the
 * token box empty while the file may still be served by something else
 * entirely — a static file uploaded over FTP, or a previous install. An empty
 * box then reads as "verification is broken" when it usually isn't:
 * verification is one-time and persisted server-side, so an already-verified
 * site stays verified with no token here at all.
 *
 * Fetching the URL is the only way to state the real situation.
 *
 * @return array{state:string,token:string,code:int} state is one of
 *         'serving' | 'foreign' | 'missing' | 'unknown'.
 */
function promovolve_wellknown_status( $configured_token ) {
	$cached = get_transient( PROMOVOLVE_WELLKNOWN_TRANSIENT );
	if ( is_array( $cached ) ) {
		return $cached;
	}

	$url      = home_url( '/.well-known/promovolve.txt' );
	$response = wp_remote_get( $url, array( 'timeout' => 5, 'redirection' => 2 ) );

	if ( is_wp_error( $response ) ) {
		// Many hosts block loopback requests. Say so rather than reporting a
		// missing file — a wrong "not served" would send publishers chasing
		// a problem that isn't there.
		$status = array( 'state' => 'unknown', 'token' => '', 'code' => 0 );
	} else {
		$code = (int) wp_remote_retrieve_response_code( $response );
		$body = trim( (string) wp_remote_retrieve_body( $response ) );
		$hit  = array();
		if ( 200 === $code && preg_match( '/promovolve-site-verification=([A-Za-z0-9-]+)/', $body, $hit ) ) {
			$found = $hit[1];
			$status = array(
				// 'foreign' = a token is being served that this plugin is not
				// the source of. Worth distinguishing: it means the publisher
				// has a static file (or another install) doing the job.
				'state' => ( '' !== $configured_token && $found === $configured_token ) ? 'serving' : 'foreign',
				'token' => $found,
				'code'  => $code,
			);
		} else {
			$status = array( 'state' => 'missing', 'token' => '', 'code' => $code );
		}
	}

	set_transient( PROMOVOLVE_WELLKNOWN_TRANSIENT, $status, MINUTE_IN_SECONDS );
	return $status;
}

/* -------------------------------------------------------------------------
 * Settings
 * ---------------------------------------------------------------------- */

add_action( 'admin_init', function () {
	register_setting( 'promovolve', PROMOVOLVE_OPTION, array(
		'type'              => 'array',
		'sanitize_callback' => 'promovolve_sanitize_settings',
	) );
} );

/**
 * @param mixed $input Raw form input.
 * @return array Clean settings.
 */
function promovolve_sanitize_settings( $input ) {
	$input = is_array( $input ) ? $input : array();
	$clean = promovolve_settings();

	if ( isset( $input['site_id'] ) ) {
		$clean['site_id'] = preg_replace( '/[^a-z0-9-]/', '', strtolower( trim( (string) $input['site_id'] ) ) );
	}

	if ( isset( $input['api_base'] ) ) {
		$api = untrailingslashit( esc_url_raw( trim( (string) $input['api_base'] ) ) );
		// The loader appends /v1 itself; a pasted ".../v1" would double it.
		$api               = preg_replace( '#/v1$#', '', $api );
		$clean['api_base'] = untrailingslashit( $api );
	}

	if ( isset( $input['script_url'] ) ) {
		$clean['script_url'] = esc_url_raw( trim( (string) $input['script_url'] ) );
	}

	if ( isset( $input['verification_token'] ) ) {
		$token = trim( (string) $input['verification_token'] );
		// Accept the full "promovolve-site-verification=<token>" line or the bare token.
		$token                       = preg_replace( '/^promovolve-site-verification=/', '', $token );
		$clean['verification_token'] = preg_replace( '/[^A-Za-z0-9-]/', '', $token );
	}

	$clean['auto_slot_enabled'] = ! empty( $input['auto_slot_enabled'] );
	if ( isset( $input['auto_slot_id'] ) ) {
		$clean['auto_slot_id'] = sanitize_text_field( (string) $input['auto_slot_id'] );
	}
	if ( isset( $input['auto_slot_scope'] ) && in_array( $input['auto_slot_scope'], array( 'site', 'category', 'post' ), true ) ) {
		$clean['auto_slot_scope'] = $input['auto_slot_scope'];
	}
	if ( isset( $input['auto_slot_w'] ) ) {
		$clean['auto_slot_w'] = max( 0, (int) $input['auto_slot_w'] );
	}
	if ( isset( $input['auto_slot_h'] ) ) {
		$clean['auto_slot_h'] = max( 0, (int) $input['auto_slot_h'] );
	}

	return $clean;
}

/**
 * Settings changes alter front-end markup (the ad tag, slot IDs), which page
 * caches have already stored — on hosts like Hostinger, LiteSpeed caches pages
 * for days and the new setting silently never applies. Purge every cache we
 * can detect; each call is a no-op when that cache plugin is absent.
 */
function promovolve_purge_page_caches() {
	// The saved token decides whether we serve the verification file at all,
	// so the live probe's answer is stale the moment settings change.
	delete_transient( PROMOVOLVE_WELLKNOWN_TRANSIENT );
	// site_id / api_base are what the verification probe asks WITH, so a
	// settings change can invalidate its answer too.
	delete_transient( PROMOVOLVE_VERIFIED_TRANSIENT );
	do_action( 'litespeed_purge_all' );
	if ( function_exists( 'wp_cache_clear_cache' ) ) {
		wp_cache_clear_cache(); // WP Super Cache
	}
	if ( function_exists( 'w3tc_flush_all' ) ) {
		w3tc_flush_all(); // W3 Total Cache
	}
	if ( function_exists( 'rocket_clean_domain' ) ) {
		rocket_clean_domain(); // WP Rocket
	}
	if ( function_exists( 'sg_cachepress_purge_cache' ) ) {
		sg_cachepress_purge_cache(); // SiteGround Optimizer
	}
	if ( function_exists( 'wpfc_clear_all_cache' ) ) {
		wpfc_clear_all_cache(); // WP Fastest Cache
	}
	if ( class_exists( 'Cache_Enabler' ) && method_exists( 'Cache_Enabler', 'clear_complete_cache' ) ) {
		Cache_Enabler::clear_complete_cache(); // Cache Enabler
	}
	do_action( 'breeze_clear_all_cache' );     // Breeze (Cloudways)
	do_action( 'wphb_clear_page_cache' );      // Hummingbird
	if ( function_exists( 'wpo_cache_flush' ) ) {
		wpo_cache_flush(); // WP-Optimize
	}
	if ( function_exists( 'wp_cache_flush' ) ) {
		wp_cache_flush(); // object cache (harmless, keeps option reads fresh)
	}
}

// BOTH hooks: WordPress fires add_option_* the FIRST time the option row is
// created and update_option_* thereafter — hooking only update means the
// very first configuration is served from cache and "doesn't apply".
add_action( 'add_option_' . PROMOVOLVE_OPTION, 'promovolve_purge_page_caches' );
add_action( 'update_option_' . PROMOVOLVE_OPTION, 'promovolve_purge_page_caches' );

add_action( 'admin_menu', function () {
	add_options_page(
		__( 'Promovolve', 'promovolve' ),
		__( 'Promovolve', 'promovolve' ),
		'manage_options',
		'promovolve',
		'promovolve_render_settings_page'
	);
} );

function promovolve_render_settings_page() {
	if ( ! current_user_can( 'manage_options' ) ) {
		return;
	}
	$s    = promovolve_settings();
	$host = promovolve_canonical_host();
	// wp_is_block_theme() landed in WP 5.9; the plugin header asks for 6.0, but
	// that header is advisory and hosts do run older cores.
	$block_theme = function_exists( 'wp_is_block_theme' ) && wp_is_block_theme();

	$tag_preview = '';
	if ( '' !== $s['site_id'] && '' !== $s['api_base'] && '' !== $s['script_url'] ) {
		$tag_preview = sprintf(
			'<script data-pub="%s" data-api="%s" src="%s"></script>',
			esc_attr( $s['site_id'] ),
			esc_attr( $s['api_base'] ),
			esc_url( $s['script_url'] )
		);
	}
	?>
	<div class="wrap">
		<h1><?php esc_html_e( 'Promovolve Publisher', 'promovolve' ); ?></h1>

		<form method="post" action="options.php">
			<?php settings_fields( 'promovolve' ); ?>

			<h2><?php esc_html_e( 'Connection', 'promovolve' ); ?></h2>
			<p><?php esc_html_e( 'All three values come from your Promovolve operator. The ad tag is printed on every front-end page once all three are set.', 'promovolve' ); ?></p>
			<table class="form-table" role="presentation">
				<tr>
					<th scope="row"><label for="promovolve-site-id"><?php esc_html_e( 'Site ID', 'promovolve' ); ?></label></th>
					<td>
						<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[site_id]" id="promovolve-site-id" type="text" class="regular-text code" value="<?php echo esc_attr( $s['site_id'] ); ?>" placeholder="<?php echo esc_attr( str_replace( '.', '-', $host ) ); ?>">
						<p class="description"><?php esc_html_e( 'Shown on the dashboard Sites page after your site is approved. Usually the domain with dots replaced by dashes.', 'promovolve' ); ?></p>
					</td>
				</tr>
				<tr>
					<th scope="row"><label for="promovolve-api-base"><?php esc_html_e( 'Ads API base URL', 'promovolve' ); ?></label></th>
					<td>
						<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[api_base]" id="promovolve-api-base" type="url" class="regular-text code" value="<?php echo esc_attr( $s['api_base'] ); ?>" placeholder="https://ads.example.com">
						<p class="description"><?php esc_html_e( 'Scheme and host only — no /v1 suffix, no trailing slash (normalized on save).', 'promovolve' ); ?></p>
					</td>
				</tr>
				<tr>
					<th scope="row"><label for="promovolve-script-url"><?php esc_html_e( 'Ad loader script URL', 'promovolve' ); ?></label></th>
					<td>
						<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[script_url]" id="promovolve-script-url" type="url" class="regular-text code" value="<?php echo esc_attr( $s['script_url'] ); ?>" placeholder="https://cdn.example.com/promovolve-ad.js">
						<p class="description"><?php esc_html_e( 'The stable promovolve-ad.js URL on your operator’s CDN.', 'promovolve' ); ?></p>
					</td>
				</tr>
			</table>

			<h2><?php esc_html_e( 'Site verification', 'promovolve' ); ?></h2>
			<?php
			$verification = promovolve_verification_status( $s );
			if ( 'verified' === $verification ) :
				// Verified: the token has no remaining purpose, so the field is
				// not rendered at all rather than left as a puzzle. Omitting the
				// input is safe — the sanitizer keeps any saved value when the
				// key is absent from the submission. If verification is ever
				// lost (a deleted-and-re-added site starts unverified), the probe
				// returns 403 and the field comes back on its own.
				?>
				<p style="padding:8px 10px;border-left:4px solid #00a32a;background:#fff;max-width:46em;">
					<strong><?php esc_html_e( 'This site is verified.', 'promovolve' ); ?></strong>
					<?php esc_html_e( 'Verification is one-time and held by the ad server, so there is nothing to configure here. The token field is hidden because it is no longer needed; it reappears automatically if this site ever needs verifying again.', 'promovolve' ); ?>
				</p>
			<?php else : ?>
			<table class="form-table" role="presentation">
				<tr>
					<th scope="row"><label for="promovolve-token"><?php esc_html_e( 'Verification token', 'promovolve' ); ?></label></th>
					<td>
						<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[verification_token]" id="promovolve-token" type="text" class="regular-text code" value="<?php echo esc_attr( $s['verification_token'] ); ?>">
						<?php if ( 'unverified' === $verification ) : ?>
							<p class="description" style="padding:8px 10px;border-left:4px solid #dba617;background:#fff;">
								<?php esc_html_e( 'The ad server does not recognise this site as verified yet. Paste the token below, then click Verify on the dashboard Sites page.', 'promovolve' ); ?>
							</p>
						<?php endif; ?>
						<p class="description"><?php esc_html_e( 'Needed only until you click Verify on the dashboard — verification is one-time. An already-verified site stays verified even with this box empty, so a blank field after reinstalling the plugin is not a fault.', 'promovolve' ); ?></p>
						<p class="description"><?php esc_html_e( 'Paste the token (or the full promovolve-site-verification=… line) from the dashboard Sites page, then click Verify there.', 'promovolve' ); ?></p>

						<?php
						// Ground truth beats the saved setting: report what the
						// URL actually returns right now.
						$wk     = promovolve_wellknown_status( $s['verification_token'] );
						$wk_url = home_url( '/.well-known/promovolve.txt' );
						$link   = '<a href="' . esc_url( $wk_url ) . '" target="_blank"><code>' . esc_html( $wk_url ) . '</code></a>';
						?>
						<p class="description" style="margin-top:10px;padding:8px 10px;border-left:4px solid <?php
							echo esc_attr( 'missing' === $wk['state'] ? '#dba617' : ( 'unknown' === $wk['state'] ? '#c3c4c7' : '#00a32a' ) );
						?>;background:#fff;">
							<?php
							switch ( $wk['state'] ) {
								case 'serving':
									printf(
										/* translators: %s: verification file URL */
										esc_html__( 'Live check: this plugin is serving the verification file at %s.', 'promovolve' ),
										$link // phpcs:ignore WordPress.Security.EscapeOutput -- built from esc_url/esc_html above.
									);
									break;
								case 'foreign':
									printf(
										/* translators: 1: verification file URL, 2: the token found there */
										esc_html__( 'Live check: %1$s already returns a token (%2$s), but it is not coming from this plugin — most likely a static file left on the server. Verification will work as-is. Paste that token above only if you want the plugin to own the file.', 'promovolve' ),
										$link, // phpcs:ignore WordPress.Security.EscapeOutput -- built from esc_url/esc_html above.
										'<code>' . esc_html( $wk['token'] ) . '</code>'
									);
									break;
								case 'missing':
									printf(
										/* translators: %s: verification file URL */
										esc_html__( 'Live check: %s returns nothing. That only matters if this site is not verified yet — check the dashboard Sites page. If it already shows as verified, no action is needed.', 'promovolve' ),
										$link // phpcs:ignore WordPress.Security.EscapeOutput -- built from esc_url/esc_html above.
									);
									break;
								default:
									printf(
										/* translators: %s: verification file URL */
										esc_html__( 'Live check: could not reach %s from this server (many hosts block loopback requests). Open it in a browser tab to see what visitors get.', 'promovolve' ),
										$link // phpcs:ignore WordPress.Security.EscapeOutput -- built from esc_url/esc_html above.
									);
							}
							?>
						</p>
						<?php if ( '' !== $s['verification_token'] ) : ?>
							<p class="description">
								<?php esc_html_e( 'DNS fallback if the file URL is unreachable (e.g. WordPress installed in a subdirectory):', 'promovolve' ); ?><br>
								<code>_promovolve.<?php echo esc_html( $host ); ?></code> TXT
								<code>promovolve-site-verification=<?php echo esc_html( $s['verification_token'] ); ?></code>
							</p>
						<?php endif; ?>
					</td>
				</tr>
			</table>
			<?php endif; // 'verified' hides the token field entirely ?>

			<h2><?php esc_html_e( 'Ad slots', 'promovolve' ); ?></h2>
			<p>
				<?php
				// Only a block theme has a Site Editor, so "put one block in your
				// Single template" is advice that simply cannot be followed on a
				// classic theme — where the automatic slot below is instead the
				// only way to cover every post without editing them.
				if ( $block_theme ) {
					esc_html_e( 'Place a slot where you want it with the “Promovolve ad slot” block — in a post or page, in a Site Editor template or template part (one placement, every post), or in a block widget area.', 'promovolve' );
				} else {
					esc_html_e( 'Place a slot where you want it with the “Promovolve ad slot” block — in a post or page, or in a block widget area.', 'promovolve' );
				}
				?>
			</p>
			<p>
				<?php esc_html_e( 'Classic editor or theme templates, use the shortcode:', 'promovolve' ); ?>
				<code>[promovolve_slot id="sidebar-top" w="300" h="250"]</code>
			</p>
			<p class="description"><?php esc_html_e( 'Slot IDs are stable names you choose per site — no pre-registration needed. A given ID renders at most once per page — repeats are dropped, since the loader could only ever fill the first one, and the block warns you about them in the editor. Exact IAB sizes are not required; creatives scale into the box you declare. Block slots append the size to the ID and offer the same identity scope as the automatic slot below; shortcode IDs are used verbatim.', 'promovolve' ); ?></p>
			<table class="form-table" role="presentation">
				<tr>
					<th scope="row"><?php esc_html_e( 'Automatic slot after post content', 'promovolve' ); ?></th>
					<td>
						<label>
							<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[auto_slot_enabled]" type="checkbox" value="1" <?php checked( $s['auto_slot_enabled'] ); ?>>
							<?php esc_html_e( 'Append an ad slot after the content on single posts and pages', 'promovolve' ); ?>
						</label>
						<p class="description">
							<?php
							if ( $block_theme ) {
								esc_html_e( 'A blanket placement for existing posts you do not want to edit. Your theme is a block theme, so putting the block in your Single template gives the same coverage and lets you choose the position — prefer that. If you use both, give them different IDs: a page fills only the first slot of any given ID.', 'promovolve' );
							} else {
								esc_html_e( 'The zero-touch option on your theme: classic themes have no Site Editor, so this is the only way to place a slot on every post without editing each one. If you also place blocks in post content, give them different IDs: a page fills only the first slot of any given ID.', 'promovolve' );
							}
							?>
						</p>
						<p style="margin-top:8px;">
							<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[auto_slot_id]" type="text" class="code" value="<?php echo esc_attr( $s['auto_slot_id'] ); ?>" aria-label="<?php esc_attr_e( 'Slot ID', 'promovolve' ); ?>">
							<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[auto_slot_w]" type="number" min="1" style="width:6em;" value="<?php echo esc_attr( $s['auto_slot_w'] ); ?>" aria-label="<?php esc_attr_e( 'Width', 'promovolve' ); ?>"> ×
							<input name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[auto_slot_h]" type="number" min="1" style="width:6em;" value="<?php echo esc_attr( $s['auto_slot_h'] ); ?>" aria-label="<?php esc_attr_e( 'Height', 'promovolve' ); ?>">
						</p>
						<p class="description">
							<?php
							printf(
								/* translators: %s: the derived slot ID */
								esc_html__( 'The size is part of the slot identity — this configuration produces slot ID %s (plus the category/post suffix below). Changing the size starts a fresh slot with its own stats; the old size&#8217;s dashboard rows remain as history.', 'promovolve' ),
								'<code>' . esc_html( $s['auto_slot_id'] . '_' . (int) $s['auto_slot_w'] . 'x' . (int) $s['auto_slot_h'] ) . '</code>'
							);
							?>
						</p>
					</td>
				</tr>
				<tr>
					<th scope="row"><label for="promovolve-slot-scope"><?php esc_html_e( 'Automatic slot identity', 'promovolve' ); ?></label></th>
					<td>
						<select name="<?php echo esc_attr( PROMOVOLVE_OPTION ); ?>[auto_slot_scope]" id="promovolve-slot-scope">
							<option value="site" <?php selected( $s['auto_slot_scope'], 'site' ); ?>><?php esc_html_e( 'Shared — one slot ID on every post', 'promovolve' ); ?></option>
							<option value="category" <?php selected( $s['auto_slot_scope'], 'category' ); ?>><?php esc_html_e( 'Per category — slot ID + the post’s category slug', 'promovolve' ); ?></option>
							<option value="post" <?php selected( $s['auto_slot_scope'], 'post' ); ?>><?php esc_html_e( 'Per post — slot ID + the post ID', 'promovolve' ); ?></option>
						</select>
						<p class="description"><?php esc_html_e( 'Each distinct slot ID becomes its own row (with its own floor learning and ad pool) on the Promovolve dashboard. Per category keeps that list small and topical — recommended for blogs. Per post gives exact page attribution but creates one row and one cold ad pool per post; avoid on large sites. Changing this later leaves the old slot rows behind on the dashboard.', 'promovolve' ); ?></p>
					</td>
				</tr>
			</table>

			<?php submit_button(); ?>
			<p class="description"><?php esc_html_e( 'Saving purges the page caches of known caching plugins automatically. If this site also sits behind an external cache or CDN (e.g. Cloudflare page caching, a host-level cache), purge that one too — otherwise visitors keep the old markup until it expires.', 'promovolve' ); ?></p>
		</form>

		<?php if ( '' !== $tag_preview ) : ?>
			<h2><?php esc_html_e( 'Active ad tag', 'promovolve' ); ?></h2>
			<p><?php esc_html_e( 'This tag is being printed in the head of every front-end page:', 'promovolve' ); ?></p>
			<pre style="background:#fff;border:1px solid #c3c4c7;padding:12px;overflow-x:auto;"><code><?php echo esc_html( $tag_preview ); ?></code></pre>
			<p class="description"><?php esc_html_e( 'Ads fill only after the site is approved and verified on the dashboard, and after each winning creative is approved in your Approval queue. Brand-new pages serve nothing on their first view while they classify — that is normal, not a fault.', 'promovolve' ); ?></p>
		<?php endif; ?>
	</div>
	<?php
}

add_filter( 'plugin_action_links_' . plugin_basename( __FILE__ ), function ( $links ) {
	array_unshift( $links, '<a href="' . esc_url( admin_url( 'options-general.php?page=promovolve' ) ) . '">' . esc_html__( 'Settings', 'promovolve' ) . '</a>' );
	return $links;
} );
