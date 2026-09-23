package com.fjf.teproject;

import org.springframework.util.ObjectUtils;

import java.util.Objects;

public class Demo {
    public static void main(String[] args) {
        String a ="123";
        Code code= new Code();

        String b= new String("123");
        String f= new String("123");

        code.setCodeValue(123);

        System.out.println(a.equals(code.getCodeValue())+"+++++++++++++");
        System.out.println(a== code.getCodeValue()+"++++++++++++");
        System.out.println(Objects.equals(a,code.getCodeValue())+"+++++++++++");

//        String c ="10009";
//
//        String d= new String("10009");
//        System.out.println(c.equals(d)+"+++++++++++++");
//        System.out.println(c==d+"++++++++++++");
//        System.out.println(Objects.equals(c,d)+"+++++++++++");
    }
}
