/*
 * Copyright 2003-2013 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.tree;

import java.nio.charset.Charset;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.w3c.dom.Node;

import com.marklogic.dom.AttrImpl;
import com.marklogic.dom.CommentImpl;
import com.marklogic.dom.DocumentImpl;
import com.marklogic.dom.ElementImpl;
import com.marklogic.dom.NodeImpl;
import com.marklogic.dom.ProcessingInstructionImpl;
import com.marklogic.dom.TextImpl;

/**
 * Java equivalent of ExpandedTreeRep in Tree.h
 * 
 * @author jchen
 */
public class ExpandedTree {
    public static final Log LOG = LogFactory.getLog(ExpandedTree.class);
	private static final Charset UTF8 = Charset.forName("UTF8");

	NodeImpl nodes[]; // NodeRep*
	
	public long ordinal;  // uint64_t
	public long uriKey;   // uint64_t
	public long uniqKey;  // uint64_t
	public long linkKey;  // uint64_t
	public long keys[];   // uint64_t*
	public byte atomData[]; // char*
	public String atomString[];
	public int atomIndex[]; // unsigned*
	
	public long nodeOrdinal[];
	public byte nodeKind[];
	public int nodeRepID[];
	public int nodeParentNodeRepID[];

	public int docNodeTextRepID[]; // unsigned DocNodeRep::textRepID
	public int docNodeChildNodeRepID[]; // unsigned DocNodeRep::childNodeRepID
	public int docNodeNumChildren[]; // unsigned DocNodeRep::numChildren

	public int elemNodeNodeNameRepID[]; // unsigned ElemNodeRep::nodeNameRepID
	public int elemNodeAttrNodeRepID[]; // unsigned ElemNodeRep::attrNodeRepID
	public int elemNodeChildNodeRepID[]; // unsigned ElemNodeRep::childNodeRepID
	public int elemNodeElemDeclRepID[]; // unsigned ElemNodeRep::elemDeclRepID
	public int elemNodeNumAttributes[]; // unsigned ElemNodeRep::numAttributes:24
	public int elemNodeNumDefaultAttrs[]; // unsigned ElemNodeRep::numDefaultAttrs:8
	public int elemNodeNumChildren[]; // unsigned ElemNodeRep::numChildren:28
	public int elemNodeFlags[]; // unsigned ElemNodeRep::flags

	public int attrNodeNodeNameRepID[];
	public int attrNodeTextRepID[];
	public int attrNodeAttrDeclRepID[];

	public int piNodeTargetAtom[];
	public int piNodeTextRepID[];

	public long linkNodeKey[];
	public long linkNodeNodeCount[];
	public int linkNodeNodeNameRepID[];
	public int linkNodeNodeRepID[];

	public int nodeNameNameAtom[];
	public int nodeNameNamespaceAtom[];

	public long nsNodeOrdinal[];
	public int nsNodePrevNSNodeRepID[];
	public int nsNodePrefixAtom[];
	public int nsNodeUriAtom[];

	public long permNodeOrdinal[];
	public int permNodePrevPermNodeRepID[];
	public Capability permNodeCapability[];
	public long permNodeRoleId[];
	
	public long binaryKey; // uint64_t BinaryNodeRep.binaryKey
	public long binaryOffset; // uint64_t BinaryNodeRep.offset
	public long binarySize; // uint64_t BinaryNodeRep.size
	public long binaryOrigLen; // uint64_t BinaryNodeRep.originalLength
	public int binaryPathAtom; // unsigned BinaryNodeRep.pathAtom

	public int numTextReps; // not in ExpandedTreeRep
	public int textReps[]; // unsigned*
	public int binaryData[]; // unsigned*
	
	public int atomLimit;  // unsigned
	public int numKeys;  // unsigned
	public int numNodeReps; // unsigned
	public int numNSNodeReps; // unsigned
	public int numPermNodeReps; // unsigned
	public int numLinkNodeReps; // unsigned
	public int uriTextRepID; // unsigned
	public int colsTextRepID; // unsigned
	public int schemaRepUID; // unsigned
	public long schemaTimestamp; // uint64_t

	private long fragmentOrdinal;

	public boolean atomEquals(int atom, byte value[]) {
		int p = 0;
		int i = atomIndex[atom] + 1;
		while (p < value.length) {
			byte b = atomData[i];
			if (LOG.isTraceEnabled()) {
			    LOG.trace(String.format("%02x %02x", b, value[p]));
			}
			if ((b == 0) || (b != value[p]))
				return false;
			p++;
			i++;
		}
		return true;
	}

	public String atomString(int i) {
		String value = null;
		if (atomString == null) {
			atomString = new String[atomIndex.length];
		} else if (atomString.length > i){
			value = atomString[i];
		}
		if (value == null) {
			value = atomString[i] = new String(atomData, atomIndex[i] + 1,
					atomIndex[i + 1] - atomIndex[i] - 2, UTF8);
		}
		return value;
	}
	
	public String getText(int index) {
	    if (textReps==null) return null;
    	StringBuilder buf = new StringBuilder();
    	for (int i=textReps[index++]; i > 0; --i) {
    	    if (LOG.isTraceEnabled()) {
    	        LOG.trace("atom " + textReps[index] + " [" + 
    	            atomString(textReps[index]) + "] length " + 
    	            atomString(textReps[index]).length());
    	    }
    		buf.append(atomString(textReps[index++]));
    	}
        if (LOG.isTraceEnabled()) {
            LOG.trace("getText(" + index + ") returning [" + buf.toString() + 
                    "] length " + buf.length());
        }
        return buf.toString();
	}
	
    public String[] getCollections() {
        int index = colsTextRepID;
        int cnt = textReps[index++];
        String[] cols = new String[cnt];
        for (int i = 0; i < cnt; ++i) {
            cols[i] = atomString(textReps[index++]);
        }
        return cols;
    }
	
	public byte rootNodeKind() {
	    if (node(0) != null) {
	        return nodeKind[((DocumentImpl)node(0)).getFirstChildIndex()];
	    } else {
	        return nodeKind[0];
	    }
	}
	
	public Node node(int i) {
		if (i == Integer.MAX_VALUE) {
			return null;
		}
		else if (nodes[i] != null) {
			return nodes[i];
		}
		else {
			switch (nodeKind[i]) {
			case NodeKind.ELEM:
				nodes[i] = new ElementImpl(this, i);
				break;
            case NodeKind.ATTR:
                nodes[i] = new AttrImpl(this, i);
                break;
			case NodeKind.TEXT:
				nodes[i] = new TextImpl(this, i);
				break;
			case NodeKind.LINK:
				break;
			case NodeKind.NS:
				break;
            case NodeKind.DOC:
                nodes[i] = new DocumentImpl(this, i);
                break;
			case NodeKind.PI:
				nodes[i] = new ProcessingInstructionImpl(this, i);
				break;
			case NodeKind.COMMENT:
				nodes[i] = new CommentImpl(this, i);
				break;
			case NodeKind.PERM:
				break;
			case NodeKind.BINARY:		
				break;
			default:
				LOG.warn("Unexpected node kind: " + nodeKind[i] + " @ " + i);
				break;
			}
			return nodes[i];
		}
	}

    public String getDocumentURI() {
        return getText(uriTextRepID);
    }
    
    public Path getPathToBinary() {
        long dirKey = binaryKey >>> 54;
        String dir = String.format("%03x", dirKey);
        String fileName = String.format("%16x", binaryKey);
        return new Path(dir, fileName);
    }

    public boolean containLinks() {
        return numLinkNodeReps > 0;
    }

    public long getFragmentOrdinal() {
        return fragmentOrdinal;
    }

    public void setFragmentOrdinal(long fragmentOrdinal) {
        this.fragmentOrdinal = fragmentOrdinal;
    }    
}
