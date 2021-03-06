/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.tools.xjc.reader.xmlschema.bindinfo;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlMixed;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.sun.codemodel.JDocComment;
import com.sun.xml.bind.v2.WellKnownNamespace;
import com.sun.tools.xjc.SchemaCache;
import com.sun.tools.xjc.model.CCustomizations;
import com.sun.tools.xjc.model.CPluginCustomization;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.reader.Ring;
import com.sun.tools.xjc.reader.xmlschema.BGMBuilder;
import com.sun.xml.bind.annotation.XmlLocation;
import com.sun.xml.bind.marshaller.MinimumEscapeHandler;
import com.sun.xml.xsom.XSComponent;

import org.w3c.dom.Element;
import org.xml.sax.Locator;

/**
 * Container for customization declarations.
 *
 * We use JAXB ourselves and parse this object from "xs:annotation".
 *
 * @author
 *     Kohsuke Kawaguchi (kohsuke,kawaguchi@sun.com)
 */
@XmlRootElement(namespace= WellKnownNamespace.XML_SCHEMA,name="annotation")
@XmlType(namespace=WellKnownNamespace.XML_SCHEMA,name="foobar")
public final class BindInfo implements Iterable<BIDeclaration> {

    private BGMBuilder builder;

    @XmlLocation
    private Locator location;
    
    /**
     * Documentation taken from {@code <xs:documentation>s}.
     */
    @XmlElement(namespace=WellKnownNamespace.XML_SCHEMA)
    private Documentation documentation;

    /**
     * Returns true if this {@link BindInfo} doesn't contain any useful
     * information.
     *
     * This flag is used to discard unused {@link BindInfo}s early to save memory footprint.
     */
    public boolean isPointless() {
        if(size()>0)     return false;
        if(documentation!=null && !documentation.contents.isEmpty())
            return false;

        return true;
    }

    private static final class Documentation {
        @XmlAnyElement
        @XmlMixed
        List<Object> contents = new ArrayList<Object>();

        void addAll(Documentation rhs) {
            if(rhs==null)   return;

            if(contents==null)
                contents = new ArrayList<Object>();
            if(!contents.isEmpty())
                contents.add("\n\n");
            contents.addAll(rhs.contents);
        }
    }

    /** list of individual declarations. */
    private final List<BIDeclaration> decls = new ArrayList<BIDeclaration>();

    private static final class AppInfo {
        /**
         * Receives {@link BIDeclaration}s and other DOMs.
         */
        @XmlAnyElement(lax=true,value=DomHandlerEx.class)
        List<Object> contents = new ArrayList<Object>();

        public void addTo(BindInfo bi) {
            if(contents==null)  return;

            for (Object o : contents) {
                if(o instanceof BIDeclaration)
                    bi.addDecl((BIDeclaration)o);
                // this is really PITA! I can't get the source location
                if(o instanceof DomHandlerEx.DomAndLocation) {
                    DomHandlerEx.DomAndLocation e = (DomHandlerEx.DomAndLocation)o;
                    String nsUri = e.element.getNamespaceURI();
                    if(nsUri==null || nsUri.equals("")
                    || nsUri.equals(WellKnownNamespace.XML_SCHEMA))
                        continue;   // this is definitely not a customization
                    bi.addDecl(new BIXPluginCustomization(e.element,e.loc));
                }
            }
        }
    }


    // only used by JAXB
    @XmlElement(namespace=WellKnownNamespace.XML_SCHEMA)
    void setAppinfo(AppInfo aib) {
        aib.addTo(this);
    }



    /**
     * Gets the location of this annotation in the source file.
     * 
     * @return
     *      If the declarations are in fact specified in the source
     *      code, a non-null valid object will be returned.
     *      If this BindInfo is generated internally by XJC, then
     *      null will be returned.
     */
    public Locator getSourceLocation() { return location; }
    
    
    private XSComponent owner;
    /**
     * Sets the owner schema component and a reference to BGMBuilder.
     * This method is called from the BGMBuilder before
     * any BIDeclaration inside it is used.
     */
    public void setOwner( BGMBuilder _builder, XSComponent _owner ) {
        this.owner = _owner;
        this.builder = _builder;
        for (BIDeclaration d : decls)
            d.onSetOwner();
    }
    public XSComponent getOwner() { return owner; }
    
    /**
     * Back pointer to the BGMBuilder which is building
     * a BGM from schema components including this customization.
     */
    public BGMBuilder getBuilder() { return builder; }

    /** Adds a new declaration. */
    public void addDecl( BIDeclaration decl ) {
        if(decl==null)  throw new IllegalArgumentException();
        decl.setParent(this);
        decls.add(decl);
    }
    
    /**
     * Gets the first declaration with a given name, or null
     * if none is found.
     */
    public <T extends BIDeclaration>
    T get( Class<T> kind ) {
        for( BIDeclaration decl : decls ) {
            if( kind.isInstance(decl) )
                return kind.cast(decl);
        }
        return null; // not found
    }
   
    /**
     * Gets all the declarations
     */ 
    public BIDeclaration[] getDecls() {
        return decls.toArray(new BIDeclaration[decls.size()]);
    }

    /**
     * Gets the documentation parsed from {@code <xs:documentation>}s.
     * The returned collection is to be added to {@link JDocComment#append(Object)}.
     * @return  maybe null.
     */
    public String getDocumentation() {
        // TODO: FIXME: correctly turn individual items to String including DOM
        if(documentation==null || documentation.contents==null) return null;

        StringBuilder buf = new StringBuilder();
        for (Object c : documentation.contents) {
            if(c instanceof String) {
                buf.append(c.toString());
            }
            if(c instanceof Element) {
                Transformer t = builder.getIdentityTransformer();
                StringWriter w = new StringWriter();
                try {
                    Writer fw = new FilterWriter(w) {
                        char[] buf = new char[1];

                        public void write(int c) throws IOException {
                            buf[0] = (char)c;
                            write(buf,0,1);
                        }

                        public void write(char[] cbuf, int off, int len) throws IOException {
                            MinimumEscapeHandler.theInstance.escape(cbuf,off,len,false,out);
                        }

                        public void write(String str, int off, int len) throws IOException {
                            write(str.toCharArray(),off,len);
                        }
                    };
                    t.transform(new DOMSource((Element)c),new StreamResult(fw));
                } catch (TransformerException e) {
                    throw new Error(e); // impossible
                }
                buf.append("\n<pre>\n");
                buf.append(w);
                buf.append("\n</pre>\n");
            }
        }
        return buf.toString();
    }

    /**
     * Merges all the declarations inside the given BindInfo
     * to this BindInfo.
     */
    public void absorb( BindInfo bi ) {
        for( BIDeclaration d : bi )
            d.setParent(this);
        this.decls.addAll( bi.decls );

        if(this.documentation==null)
            this.documentation = bi.documentation;
        else
            this.documentation.addAll(bi.documentation);
    }
    
    /** Gets the number of declarations. */
    public int size() { return decls.size(); }
    
    public BIDeclaration get( int idx ) { return decls.get(idx); }

    public Iterator<BIDeclaration> iterator() {
        return decls.iterator();
    }

    /**
     * Gets the list of {@link CPluginCustomization}s from this.
     *
     * <p>
     * Note that calling this method marks all those plug-in customizations
     * as 'used'. So call it only when it's really necessary.
     */
    public CCustomizations toCustomizationList() {
        CCustomizations r=null;
        for( BIDeclaration d : this ) {
            if(d instanceof BIXPluginCustomization) {
                BIXPluginCustomization pc = (BIXPluginCustomization) d;
                pc.markAsAcknowledged();
                if(!Ring.get(Model.class).options.pluginURIs.contains(pc.getName().getNamespaceURI()))
                    continue;   // this isn't a plugin customization
                if(r==null)
                    r = new CCustomizations();
                r.add(new CPluginCustomization(pc.element,pc.getLocation()));
            }
        }

        if(r==null)     r = CCustomizations.EMPTY;
        return new CCustomizations(r);
    }
    /** An instance with the empty contents. */
    public final static BindInfo empty = new BindInfo();

    /**
     * Lazily prepared {@link JAXBContext}.
     */
    private static volatile JAXBContext customizationContext;

    public static JAXBContext getCustomizationContext() {
        try {
            if (customizationContext == null) {
                synchronized (BindInfo.class) {
                    if (customizationContext == null) {
                        customizationContext = JAXBContext.newInstance(
                                BindInfo.class, // for xs:annotation
                                BIClass.class,
                                BIConversion.User.class,
                                BIConversion.UserAdapter.class,
                                BIDom.class,
                                BIFactoryMethod.class,
                                BIInlineBinaryData.class,
                                BIXDom.class,
                                BIXSubstitutable.class,
                                BIEnum.class,
                                BIEnumMember.class,
                                BIGlobalBinding.class,
                                BIProperty.class,
                                BISchemaBinding.class);
                    }
                }
            }
            return customizationContext;
        } catch (JAXBException e) {
            throw new AssertionError(e);
        }
    }

    public static Unmarshaller getCustomizationUnmarshaller() {
        try {
            return getCustomizationContext().createUnmarshaller();
        } catch (JAXBException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Lazily parsed schema for the binding file.
     */
    public static final SchemaCache bindingFileSchema = new SchemaCache("binding.xsd", BindInfo.class, true);
}

