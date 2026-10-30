import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

final class Xml {
    static Document read(Path file) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }
    static List<Element> children(Element parent,String tag) {
        List<Element> result=new ArrayList<>();
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e && e.getTagName().equals(tag))result.add(e);
        return result;
    }
    static Element child(Element parent,String tag) {return children(parent,tag).stream().findFirst().orElse(null);}
    static String text(Element parent,String tag) {var e=child(parent,tag);return e==null?"":e.getTextContent().trim();}
    static Element ensure(Element parent,String tag) {var e=child(parent,tag);if(e==null){e=parent.getOwnerDocument().createElement(tag);parent.appendChild(e);}return e;}
    static void set(Element parent,String tag,String text) {ensure(parent,tag).setTextContent(text);}
    static void write(Document document,Path file)throws Exception {
        var transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.INDENT,"yes");
        transformer.transform(new DOMSource(document),new StreamResult(file.toFile()));
    }
}
