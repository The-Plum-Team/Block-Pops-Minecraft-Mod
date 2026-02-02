package com.theplumteam.client.config;

/**
 * Client-side configuration for BlockPops
 * Stores settings like background color preferences
 */
public class ClientConfig {
    private static ClientConfig instance;

    // Star color settings (RGB 0.0-1.0)
    public float starColorR = 1.0f;
    public float starColorG = 1.0f;
    public float starColorB = 1.0f;
    public float starOpacity = 0.20f;  // Default opacity (20%)

    // Background color settings (RGB 0.0-1.0)
    public float backgroundColorR = 0.0f;
    public float backgroundColorG = 0.0f;
    public float backgroundColorB = 0.0f;

    // Panel opacity (0.0-1.0)
    public float panelOpacity = 1.0f;  // Default 100%

    // Color transition animation toggle
    public boolean enableColorTransition = true;  // Default enabled

    private ClientConfig() {
        // Private constructor for singleton
    }

    public static ClientConfig getInstance() {
        if (instance == null) {
            instance = new ClientConfig();
        }
        return instance;
    }

    /**
     * Get the star color as an array [R, G, B]
     */
    public float[] getStarColor() {
        return new float[]{starColorR, starColorG, starColorB};
    }

    /**
     * Set the star color from RGB values (0.0-1.0)
     */
    public void setStarColor(float r, float g, float b) {
        this.starColorR = Math.max(0.0f, Math.min(1.0f, r));
        this.starColorG = Math.max(0.0f, Math.min(1.0f, g));
        this.starColorB = Math.max(0.0f, Math.min(1.0f, b));
    }

    /**
     * Get the background color as an array [R, G, B]
     */
    public float[] getBackgroundColor() {
        return new float[]{backgroundColorR, backgroundColorG, backgroundColorB};
    }

    /**
     * Set the background color from RGB values (0.0-1.0)
     */
    public void setBackgroundColor(float r, float g, float b) {
        this.backgroundColorR = Math.max(0.0f, Math.min(1.0f, r));
        this.backgroundColorG = Math.max(0.0f, Math.min(1.0f, g));
        this.backgroundColorB = Math.max(0.0f, Math.min(1.0f, b));
    }

    /**
     * Reset to default colors (white stars, black background)
     */
    public void resetColors() {
        // Reset stars to white
        this.starColorR = 1.0f;
        this.starColorG = 1.0f;
        this.starColorB = 1.0f;
        this.starOpacity = 0.20f;

        // Reset background to black
        this.backgroundColorR = 0.0f;
        this.backgroundColorG = 0.0f;
        this.backgroundColorB = 0.0f;

        // Reset panel opacity
        this.panelOpacity = 1.0f;

        // Reset color transition toggle
        this.enableColorTransition = true;
    }
}
