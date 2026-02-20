package tterrag1112.life_in_the_village.Cultures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Culture {
    public static List<Culture> ListCultures = new ArrayList<>();
    private static HashMap<String, Culture> cultures = new HashMap<>();
    public String key;


    public Culture(String name){
        this.key = name;
    }


    public String getName(){
         return key;
    }


}
