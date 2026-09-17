package dev.vitrail.render;

import dev.vitrail.Vitrail;

import java.lang.reflect.Method;

/**
 * Reflection-only probe for an external temporal upscaler (e.g. the Upscaled mod's DLSS/FSR3
 * pipeline). Hard dependencies are forbidden: Upscaled may be absent, in which case every answer
 * is false and the engine behaves exactly as before.
 * <p>
 * Why this exists: Upscaled renders the world into a low-res target and runs DLSS/FSR
 * <em>inside</em> {@code GameRenderer.renderLevel}, at the same AFTER-{@code LevelRenderer.render}
 * point where Vitrail composites ({@code AfterLevelMixin} vs {@code upscaleWorldBeforeHand}).
 * When both engage, two authorities disagree about what size {@code mainRenderTarget()} is:
 * Upscaled's hijack says render size, Vitrail's own {@link RenderScale} says its own scaled size,
 * and the pack sizes itself from whichever it reads mid-frame. The observed crash is the GUI
 * opening a full-window scissor (3840 wide) on a render area that is still the DLSS render size
 * (2560 wide). DLAA never crashes because render == output there.
 * <p>
 * v1 contract, Vitrail-only:
 * <ul>
 * <li>When an external upscaler is actively resolving ({@link #isActive()}), Vitrail's own
 * spatial scaler stands down ({@link RenderScale} refuses {@code beginWorld}), so there is never
 * a double scale.</li>
 * <li>Vitrail's composite is ordered <em>before</em> the external evaluate (see
 * {@code AfterLevelMixin} priority), so the whole pack runs at the one hijacked render size and
 * DLSS upscales the composited image. Tonemap-before-SR is not NVIDIA-ideal, but it is
 * crash-free, and it leaves the DLSS 5 Swapper neural-rendering stage (which hooks the NGX
 * evaluate output as the final stage) operating on the shader image, which is what the user
 * asked for.</li>
 * </ul>
 */
public final class ExternalUpscaler {

	private static final String UPSCALERS = "org.relativelyboring.upscaled.upscaler.Upscalers";
	private static final String DLSS_CONFIG = "org.relativelyboring.upscaled.config.DlssConfig";

	/** Null until first probe; false forever when the class is absent. */
	private static volatile Boolean present;
	private static volatile Method readyMethod;
	private static volatile Method configGetMethod;
	private static volatile java.lang.reflect.Field enabledField;
	private static volatile boolean logged;

	/**
	 * Whether the redirect flag's field has been looked up yet, and what was found. Null until
	 * the first release attempt; tristate because absent is a stable answer.
	 */
	private static volatile Boolean redirectProbed;
	private static volatile java.lang.reflect.Field redirectField;
	private static volatile boolean redirectLogged;

	private ExternalUpscaler() {
	}

	private static void probe() {
		if (present != null) {
			return;
		}

		synchronized (ExternalUpscaler.class) {
			if (present != null) {
				return;
			}

			try {
				Class<?> upscalers = Class.forName(UPSCALERS);
				readyMethod = upscalers.getMethod("ready");
				Class<?> config = Class.forName(DLSS_CONFIG);
				configGetMethod = config.getMethod("get");
				enabledField = config.getField("dlssEnabled");
				present = Boolean.TRUE;
			} catch (Throwable t) {
				present = Boolean.FALSE;
				readyMethod = null;
				configGetMethod = null;
				enabledField = null;
			}
		}
	}

	/**
	 * Whether an external temporal upscaler is currently resolved <em>and enabled</em> and will
	 * hijack {@code mainRenderTarget()} this frame. False when Upscaled is absent, its device is
	 * not initialised, the player turned the toggle off ({@code dlssEnabled=false}, in which case
	 * Upscaled leaves the frame alone and Vitrail must behave exactly as before), or reflection
	 * fails. Cheap enough to call per frame: two cached reflective calls.
	 */
	public static boolean isActive() {
		probe();
		if (!present || readyMethod == null) {
			return false;
		}

		try {
			if (!Boolean.TRUE.equals(readyMethod.invoke(null))) {
				return false;
			}

			// ready() latches at device init from the backend choice alone; the per-frame toggle
			// lives in DlssConfig. Only report active when Upscaled will actually redirect the
			// world phase this frame.
			if (configGetMethod != null && enabledField != null) {
				Object config = configGetMethod.invoke(null);
				if (config != null && !enabledField.getBoolean(config)) {
					return false;
				}
			}

			return true;
		} catch (Throwable t) {
			if (!logged) {
				logged = true;
				Vitrail.logger().warn("External upscaler probe failed; assuming inactive", t);
			}

			return false;
		}
	}

	/**
	 * Releases an external low-res world redirect the frame is otherwise done with. Called on
	 * frames where the level render is skipped after the redirect was already engaged for it:
	 * the external evaluate that would have ended the redirect sits past the skipped call, so
	 * without this the hand and the interface that follow render into the small target and the
	 * first full-window scissor throws on it.
	 * <p>
	 * Reflection only, silent where there is nothing to release: no upscaler installed, an
	 * upscaler whose redirect is already down, or a failure to reach the flag all answer false
	 * and change nothing.
	 *
	 * @param renderer the game's renderer, carrying the merged redirect flag where an external
	 *                 upscaler mixed one in
	 * @return whether a live redirect was found and lowered
	 */
	public static boolean releaseWorldRedirect(Object renderer) {
		if (renderer == null) {
			return false;
		}

		if (redirectProbed == null) {
			synchronized (ExternalUpscaler.class) {
				if (redirectProbed == null) {
					java.lang.reflect.Field found = null;

					try {
						found = renderer.getClass().getDeclaredField(
								"upscaled$renderingWorldToLowRes");
						found.setAccessible(true);
					} catch (Throwable ignored) {
						found = null;
					}

					redirectField = found;
					redirectProbed = found != null;
				}
			}
		}

		java.lang.reflect.Field flag = redirectField;
		if (flag == null) {
			return false;
		}

		try {
			if (!flag.getBoolean(renderer)) {
				return false;
			}

			flag.setBoolean(renderer, false);
			if (!redirectLogged) {
				redirectLogged = true;
				Vitrail.logger().info("An external low-res world redirect outlived its level "
						+ "render and was lowered, so the interface draws at full size");
			}

			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}
}
