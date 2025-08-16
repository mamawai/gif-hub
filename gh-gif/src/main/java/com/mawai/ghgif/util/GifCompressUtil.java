//package com.mawai.ghgif.util;
//
//import com.madgag.gif.fmsware.AnimatedGifEncoder;
//import com.madgag.gif.fmsware.GifDecoder;
//import lombok.Getter;
//import lombok.Setter;
//import net.coobird.thumbnailator.Thumbnails;
//import org.springframework.web.multipart.MultipartFile;
//
//import javax.imageio.ImageIO;
//import java.awt.image.BufferedImage;
//import java.io.ByteArrayInputStream;
//import java.io.ByteArrayOutputStream;
//import java.io.IOException;
//
///**
// * GIF压缩工具类
// */
//public class GifCompressUtil {
//
//    /**
//     * 解析GIF文件
//     * @param gifData GIF文件字节数组
//     * @return 解码后的GIF对象
//     */
//    private static GifDecoder decodeGif(byte[] gifData) {
//        GifDecoder decoder = new GifDecoder();
//        decoder.read(new ByteArrayInputStream(gifData));
//        return decoder;
//    }
//
//    /**
//     * 压缩GIF文件并返回GIF信息
//     * @param gifFile 原始GIF文件
//     * @param quality 压缩质量 (0.1-1.0)
//     * @param maxWidth 最大宽度
//     * @param maxHeight 最大高度
//     * @return 包含压缩后数据和GIF信息的结果对象
//     * @throws IOException IO异常
//     */
//    public static CompressResult processGif(MultipartFile gifFile, float quality, int maxWidth, int maxHeight) throws IOException {
//        // 将MultipartFile转换为字节数组
//        byte[] gifBytes = gifFile.getBytes();
//
//        // 解码GIF
//        GifDecoder decoder = decodeGif(gifBytes);
//
//        // 获取GIF信息
//        int frameCount = decoder.getFrameCount();
//        int originalWidth = decoder.getFrameSize().width;
//        int originalHeight = decoder.getFrameSize().height;
//
//        // 创建GIF信息对象
//        GifInfo info = new GifInfo();
//        info.setWidth(originalWidth);
//        info.setHeight(originalHeight);
//        info.setFrameCount(frameCount);
//
//        // 计算总时长（毫秒）
//        int duration = 0;
//        for (int i = 0; i < frameCount; i++) {
//            duration += decoder.getDelay(i);
//        }
//        info.setDuration(duration);
//
//        // 计算缩放比例
//        double scale = calculateScale(originalWidth, originalHeight, maxWidth, maxHeight);
//        int targetWidth = (int) (originalWidth * scale);
//        int targetHeight = (int) (originalHeight * scale);
//
//        // 如果不需要缩放且质量为1.0，直接返回原始数据
//        if (scale >= 1.0 && quality >= 1.0) {
//            return new CompressResult(gifBytes, info);
//        }
//
//        // 创建GIF编码器
//        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
//        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//        encoder.start(outputStream);
//
//        // 设置重复次数（0表示无限循环）
//        encoder.setRepeat(decoder.getLoopCount());
//
//        // 处理每一帧
//        for (int i = 0; i < frameCount; i++) {
//            // 获取当前帧
//            BufferedImage frame = decoder.getFrame(i);
//
//            // 获取当前帧延迟时间
//            int delay = decoder.getDelay(i);
//            encoder.setDelay(delay);
//
//            // 缩放和压缩当前帧
//            BufferedImage resizedFrame = resizeFrame(frame, targetWidth, targetHeight, quality);
//
//            // 添加到编码器
//            encoder.addFrame(resizedFrame);
//        }
//
//        // 完成编码
//        encoder.finish();
//
//        // 返回压缩后的GIF字节数组和信息
//        return new CompressResult(outputStream.toByteArray(), info);
//    }
//
//    /**
//     * 计算缩放比例
//     */
//    private static double calculateScale(int width, int height, int maxWidth, int maxHeight) {
//        double widthScale = 1.0;
//        double heightScale = 1.0;
//
//        if (maxWidth > 0 && width > maxWidth) {
//            widthScale = (double) maxWidth / width;
//        }
//
//        if (maxHeight > 0 && height > maxHeight) {
//            heightScale = (double) maxHeight / height;
//        }
//
//        // 返回较小的缩放比例，确保图片完全适应目标尺寸
//        return Math.min(widthScale, heightScale);
//    }
//
//    /**
//     * 缩放和压缩单个帧
//     */
//    private static BufferedImage resizeFrame(BufferedImage frame, int targetWidth, int targetHeight, float quality) throws IOException {
//        // 使用thumbnailator库进行缩放和压缩
//        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//        Thumbnails.of(frame)
//                .size(targetWidth, targetHeight)
//                .outputQuality(quality)
//                .outputFormat("jpg")
//                .toOutputStream(outputStream);
//
//        // 将输出流转换回BufferedImage
//        return ImageIO.read(new ByteArrayInputStream(outputStream.toByteArray()));
//    }
//
//    /**
//     * GIF压缩结果类
//     */
//    public record CompressResult(byte[] compressedData, GifInfo gifInfo) {
//    }
//
//    /**
//     * GIF信息类
//     */
//    @Setter
//    @Getter
//    public static class GifInfo {
//        private int width;
//        private int height;
//        private int frameCount;
//        private int duration;
//    }
//}