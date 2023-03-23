package org.apache.hadoop.fs.azurebfs;

import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Stack;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants;

/**
 * Sets name, metadata, content-length on {@link BlobProperty} object for now.
 * Generic class which can be extended for more fields.
 * <a href="https://learn.microsoft.com/en-us/rest/api/storageservices/list-blobs?tabs=azure-ad#response-body">
 * BlobList API XML response example</a>
 */
public class BlobListXmlParser extends DefaultHandler {
  private final BlobList blobList;
  private BlobProperty currentBlobProperty;
  private StringBuilder bld = new StringBuilder();
  private final Stack<String> elements = new Stack<>();

  public BlobListXmlParser(final BlobList blobList) {
    this.blobList = blobList;
  }

  @Override
  public void startElement(final String uri,
      final String localName,
      final String qName,
      final Attributes attributes) throws SAXException {
    elements.push(localName);
    if (AbfsHttpConstants.BLOB.equals(localName)) {
      currentBlobProperty = new BlobProperty();
    }
  }

  @Override
  public void endElement(final String uri,
      final String localName,
      final String qName)
      throws SAXException {
    String currentNode = elements.pop();
    if (!currentNode.equals(localName)) {
      throw new SAXException("Invalid XML");
    }
    String parentNode = "";
    if (elements.size() > 0) {
      parentNode = elements.peek();
    }

    String value = bld.toString();
    if (value.isEmpty()) {
      value = null;
    }

    if (AbfsHttpConstants.BLOB.equals(currentNode)) {
      blobList.addBlobProperty(currentBlobProperty);
      currentBlobProperty.setIsDirectory(null);
      currentBlobProperty = null;
    }

    if (AbfsHttpConstants.NEXT_MARKER.equals(currentNode)) {
      blobList.setNextMarker(value);
    }

    if (parentNode.equals(AbfsHttpConstants.BLOB_PREFIX)) {
      if (currentNode.equals(AbfsHttpConstants.NAME)) {
        currentBlobProperty.setBlobPrefix(value);
      }
    }
    if (parentNode.equals(AbfsHttpConstants.BLOB)) {
      if (currentNode.equals(AbfsHttpConstants.NAME)) {
        currentBlobProperty.setName(value);
      }
    }
    if (parentNode.equals(AbfsHttpConstants.METADATA)) {
      currentBlobProperty.addMetadata(currentNode, value);
    }
    if (parentNode.equals(AbfsHttpConstants.PROPERTIES)) {
      if (currentNode.equals(AbfsHttpConstants.CONTENT_LEN)) {
        currentBlobProperty.setContentLength(Long.valueOf(value));
      }
      if (currentNode.equals(AbfsHttpConstants.RESOURCE_TYPE)) {
        if ("directory".equals(value)) {
          currentBlobProperty.setIsDirectory(true);
        }
      }
    }
    bld = new StringBuilder();
  }

  @Override
  public void characters(final char[] ch, final int start, final int length)
      throws SAXException {
    bld.append(ch, start, length);
  }
}
