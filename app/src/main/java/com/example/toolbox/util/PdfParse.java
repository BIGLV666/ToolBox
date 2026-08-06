package com.example.toolbox.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class PdfParse {
    private final Context context;
    public PdfParse (Context context){
        this.context=context;
    }
    public String parseFromPath(String path)throws Exception{
        try(InputStream inputStream=new java.io.FileInputStream(path)){
            return parseFromSteam(inputStream);
        }
    }
    public String parseFromByte(byte[]data) throws Exception{
        try(InputStream inputStream=new ByteArrayInputStream(data)) {
            return parseFromSteam(inputStream);
        }
    }

    public String parseFromUri(Uri uri)throws Exception{
        ContentResolver resolver=context.getContentResolver();
        try(InputStream inputStream=resolver.openInputStream(uri)){
            return parseFromSteam(inputStream);
        }
    }
    private String parseFromSteam(InputStream inputStream)throws Exception{
        try(PDDocument d=PDDocument.load(inputStream)){
            PDFTextStripper stripper=new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(d.getNumberOfPages());
            return stripper.getText(d);
        }
    }


}
