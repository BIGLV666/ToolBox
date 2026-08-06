package com.example.toolbox.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class PdfParse {
    private final Context context;
    public PdfParse (Context context){
        this.context=context;
    }
    public String parseFromPath(String path)throws Exception{
        try(InputStream inputStream=new java.io.FileInputStream(path)){
            return parse(inputStream);
        }
    }
    public String parseFromByte(byte[]data) throws Exception{
        try(InputStream inputStream=new ByteArrayInputStream(data)) {
            return parse(inputStream);
        }
    }

    public String parseFromUri(Uri uri)throws Exception{
        ContentResolver resolver=context.getContentResolver();
        try(InputStream inputStream=resolver.openInputStream(uri)){
            if (inputStream == null) throw new IllegalArgumentException("无法读取 PDF 文件");
            return parse(inputStream);
        }
    }
    public String parse(InputStream inputStream)throws Exception{
        if (inputStream == null) throw new IllegalArgumentException("无法读取 PDF 文件");
        try(PDDocument d=PDDocument.load(inputStream)){
            PDFTextStripper stripper=new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(d.getNumberOfPages());
            String text = stripper.getText(d).trim();
            if (text.isEmpty()) throw new IllegalArgumentException("PDF 文件中没有可读取的文本");
            return text;
        }
    }


}
