package com.cloudagent.business.dto;

/** 上传用例的数据载体，不暴露 Servlet/MultipartFile 给业务层。 字节仅用于本次请求，业务层仍负责类型、大小及图片像素验证。 */
public record UploadData(String originalFilename, byte[] bytes) {}
