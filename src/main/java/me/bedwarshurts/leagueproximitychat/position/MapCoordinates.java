package me.bedwarshurts.leagueproximitychat.position;


final class MapCoordinates {

    private static final float PERSPECTIVE_COMPENSATION_X = 0.68f;
    private static final float PERSPECTIVE_COMPENSATION_Y = 0.75f;
    private static final float FALLBACK_CAMERA_RATIO = 0.021f;

    private static final float HEALTH_BAR_TO_FEET = 0.074f;

    private MapCoordinates() {
    }

    static float percentX(double pixelX, int mapSize) {
        return ((float) pixelX / mapSize) * 100f;
    }

    static float percentY(double pixelY, int mapSize) {
        return 100f - (((float) pixelY / mapSize) * 100f);
    }

    static double percentXPrecise(double pixelX, int mapSize) {
        return (pixelX / mapSize) * 100.0;
    }

    static double percentYPrecise(double pixelY, int mapSize) {
        return 100.0 - ((pixelY / mapSize) * 100.0);
    }

    static double pixelX(double percentX, int mapSize) {
        return (percentX / 100.0) * mapSize;
    }

    static double pixelY(double percentY, int mapSize) {
        return ((100.0 - percentY) / 100.0) * mapSize;
    }

    static float projectHealthBarX(double healthBarX, CameraBox cameraBox, int mapSize, int screenWidth) {
        float cameraCenterX = (float) cameraBox.center().x;
        float dynamicRatioX = (cameraBox.width() > 0)
                ? ((float) cameraBox.width() * PERSPECTIVE_COMPENSATION_X) / screenWidth
                : FALLBACK_CAMERA_RATIO;

        float offsetX = (float) healthBarX - (screenWidth / 2.0f);
        float finalX = cameraCenterX + (offsetX * dynamicRatioX);
        return (finalX / mapSize) * 100f;
    }

    static float projectHealthBarY(double healthBarY, CameraBox cameraBox, int mapSize, int screenHeight) {
        float feetY = (float) (healthBarY + (screenHeight * HEALTH_BAR_TO_FEET));
        float cameraCenterY = (float) cameraBox.center().y;
        float dynamicRatioY = (cameraBox.height() > 0)
                ? ((float) cameraBox.height() * PERSPECTIVE_COMPENSATION_Y) / screenHeight
                : FALLBACK_CAMERA_RATIO;

        float offsetY = feetY - (screenHeight / 2.0f);
        float finalY = cameraCenterY + (offsetY * dynamicRatioY);
        return 100f - ((finalY / mapSize) * 100f);
    }
}
