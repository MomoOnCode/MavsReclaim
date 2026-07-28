package mavsreclaim;

// Raw image bytes plus their content type (e.g. "image/jpeg"), used when
// streaming a stored photo back to the browser.
public record Photo(byte[] data, String type) {}
