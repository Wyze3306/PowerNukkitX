package org.powernukkitx.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import lombok.experimental.UtilityClass;
import org.cloudburstmc.protocol.bedrock.data.skin.Skin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@UtilityClass
public class SkinUtils {

    public SerializedImage getImage(JsonObject token, String name) {
        if (token.has(name + "Data")) {
            byte[] skinImage = Base64.getDecoder().decode(token.get(name + "Data").getAsString());
            if (token.has(name + "ImageHeight") && token.has(name + "ImageWidth")) {
                int width = token.get(name + "ImageWidth").getAsInt();
                int height = token.get(name + "ImageHeight").getAsInt();
                return new SerializedImage(width, height, skinImage);
            } else {
                return SerializedImage.fromLegacy(skinImage);
            }
        }
        return SerializedImage.EMPTY;
    }

    public PersonaPiece getPersonaPiece(JsonObject object) {
        String pieceId = object.get("PieceId").getAsString();
        String pieceType = object.get("PieceType").getAsString();
        String packId = object.get("PackId").getAsString();
        boolean isDefault = object.get("IsDefault").getAsBoolean();
        String productId = object.get("ProductId").getAsString();
        return new PersonaPiece(pieceId, pieceType, packId, isDefault, productId);
    }

    public PersonaPieceTint getTint(JsonObject object) {
        String pieceType = object.get("PieceType").getAsString();
        List<String> colors = new ArrayList<>();
        for (JsonElement element : object.get("Colors").getAsJsonArray()) {
            colors.add(element.getAsString()); // remove #
        }
        return new PersonaPieceTint(pieceType, colors);
    }

    /** Largest skin, cape or animation frame a client may present: 512×512 RGBA. */
    private static final int MAX_IMAGE_SIDE = 512;
    /** Persona geometry is the largest JSON a skin carries. */
    private static final int MAX_GEOMETRY_LENGTH = 1024 * 1024;

    /**
     * Whether an image has sane dimensions and exactly the bytes they call for. The skin is
     * broadcast to every player, so an oversized one costs every connection, not just the sender's.
     */
    private boolean isValidImage(org.cloudburstmc.protocol.bedrock.data.skin.ImageData image) {
        if (image == null) {
            return true;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 0 || height < 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE) {
            return false;
        }
        return image.getImage() == null || image.getImage().length == width * height * 4;
    }

    public boolean isValid(Skin skin) {
        if (!isValidImage(skin.getSkinData()) || !isValidImage(skin.getCapeData())
                || (skin.getGeometryData() != null && skin.getGeometryData().length() > MAX_GEOMETRY_LENGTH)) {
            return false;
        }
        for (var animation : skin.getAnimations()) {
            if (!isValidImage(animation.getImage())) {
                return false;
            }
        }
        return skin.getSkinId() != null && !skin.getSkinId().trim().isEmpty() && skin.getSkinId().length() < 100 &&
                skin.getSkinData() != null && skin.getSkinData().getWidth() >= 32 && skin.getSkinData().getHeight() >= 32 &&
                skin.getSkinData().getImage().length >= Skin.SINGLE_SKIN_SIZE &&
                (skin.getPlayFabId() == null || skin.getPlayFabId().length() < 100) &&
                (skin.getCapeId() == null || skin.getCapeId().length() < 100) &&
                (skin.getSkinColor() == null || skin.getSkinColor().length() < 100) &&
                (skin.getArmSize() == null || skin.getArmSize().length() < 100) &&
                (skin.getFullSkinId() == null || skin.getFullSkinId().length() < 200) &&
                (skin.getGeometryDataEngineVersion() == null || skin.getGeometryDataEngineVersion().length() < 100) &&
                (skin.getAnimationData() == null || skin.getAnimationData().length() < 1000) &&
                skin.getAnimations().size() <= 100 &&
                skin.getPersonaPieces().size() <= 100 &&
                skin.getTintColors().size() <= 100 && isValidResourcePatch(skin.getSkinResourcePatch());
    }

    public boolean isValidResourcePatch(String skinResourcePatch) {
        if (skinResourcePatch == null || skinResourcePatch.length() > 1000) {
            return false;
        }
        try {
            JsonObject object = Utils.GSON.fromJson(skinResourcePatch, JsonObject.class);
            JsonObject geometry = object.getAsJsonObject("geometry");
            return geometry.has("default") && geometry.get("default").isJsonPrimitive() && geometry.get("default").getAsJsonPrimitive().isString();
        } catch (ClassCastException | NullPointerException | JsonSyntaxException e) {
            return false;
        }
    }

    public static String convertGeometryName(String geometryName) {
        return "{\"geometry\" : {\"default\" : \"" + geometryName + "\"}}";
    }
}
