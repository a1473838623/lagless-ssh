package dev.lagless;

import java.util.Locale;

/** Two-language user interface text; Chinese stays the default until a language is chosen. */
public final class I18n {
    public enum Language { ZH("zh","简体中文"), EN("en","English");
        public final String code, label;
        Language(String code,String label){this.code=code;this.label=label;}
        public static Language parse(String code){
            if(code==null)return null;
            for(Language language:values())if(language.code.equalsIgnoreCase(code.trim()))return language;
            return null;
        }
        public static Language system(){return Locale.getDefault().getLanguage().equals("zh")?ZH:EN;}
    }
    private static volatile Language current=Language.ZH;
    private I18n(){}
    public static Language language(){return current;}
    public static void setLanguage(Language language){if(language!=null)current=language;}
    public static boolean english(){return current==Language.EN;}
    /** Returns the text for the current language. */
    public static String t(String zh,String en){return current==Language.EN?en:zh;}
}
