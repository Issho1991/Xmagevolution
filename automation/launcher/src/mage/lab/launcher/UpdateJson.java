package mage.lab.launcher;

import java.io.IOException;
import java.util.*;

/** Small strict JSON reader for the official metadata; no executable deserialization. */
final class UpdateJson {
    private final String s; private int pos;
    private UpdateJson(String s) { this.s=s; }
    static Map<String,Object> object(String s) throws IOException {
        UpdateJson p=new UpdateJson(s); Object v=p.value(0);p.ws();
        if(p.pos!=s.length()||!(v instanceof Map))throw new IOException("Formato del canale ufficiale non riconosciuto.");
        return (Map<String,Object>)v;
    }
    private void ws(){while(pos<s.length()&&" \t\r\n".indexOf(s.charAt(pos))>=0)pos++;}
    private char take() throws IOException {if(pos>=s.length())throw bad();return s.charAt(pos++);}
    private IOException bad(){return new IOException("Metadati JSON non validi (posizione "+pos+").");}
    private Object value(int depth)throws IOException {
        ws();if(depth>16||pos>=s.length())throw bad();char c=take();
        if(c=='"')return string();
        if(c=='{') {Map<String,Object> m=new LinkedHashMap<>();ws();if(pos<s.length()&&s.charAt(pos)=='}'){pos++;return m;}
            while(true){ws();if(take()!='"')throw bad();String k=string();ws();if(take()!=':'||m.containsKey(k))throw bad();m.put(k,value(depth+1));ws();char end=take();if(end=='}')return m;if(end!=',')throw bad();}
        }
        if(c=='['){List<Object> a=new ArrayList<>();ws();if(pos<s.length()&&s.charAt(pos)==']'){pos++;return a;}
            while(true){a.add(value(depth+1));ws();char end=take();if(end==']')return a;if(end!=',')throw bad();}
        }
        int start=pos-1;while(pos<s.length()&&",]} \t\r\n".indexOf(s.charAt(pos))<0)pos++;String token=s.substring(start,pos);
        if(token.equals("true"))return Boolean.TRUE;if(token.equals("false"))return Boolean.FALSE;if(token.equals("null"))return null;
        if(token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))return token;throw bad();
    }
    private String string()throws IOException {
        StringBuilder b=new StringBuilder();while(true){char c=take();if(c=='"')return b.toString();if(c<32)throw bad();
            if(c=='\\'){char e=take();if(e=='u'){int n=0;for(int j=0;j<4;j++){int h=Character.digit(take(),16);if(h<0)throw bad();n=n*16+h;}c=(char)n;}
                else {int i="\"\\/bfnrt".indexOf(e);if(i<0)throw bad();c="\"\\/\b\f\n\r\t".charAt(i);}}
            b.append(c);
        }
    }
}
