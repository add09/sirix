/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.HashBiMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import org.sirix.access.conf.ResourceConfiguration.Builder.EConsistency;
import org.sirix.api.IAxis;
import org.sirix.api.INodeReadTrx;
import org.sirix.api.INodeWriteTrx;
import org.sirix.api.IPageWriteTrx;
import org.sirix.axis.DescendantAxis;
import org.sirix.axis.PostOrderAxis;
import org.sirix.exception.AbsTTException;
import org.sirix.exception.TTIOException;
import org.sirix.exception.TTThreadedException;
import org.sirix.exception.TTUsageException;
import org.sirix.node.AttributeNode;
import org.sirix.node.DocumentRootNode;
import org.sirix.node.ENode;
import org.sirix.node.ElementNode;
import org.sirix.node.NamespaceNode;
import org.sirix.node.TextNode;
import org.sirix.node.delegates.NameNodeDelegate;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.delegates.StructNodeDelegate;
import org.sirix.node.delegates.ValNodeDelegate;
import org.sirix.node.interfaces.INameNode;
import org.sirix.node.interfaces.INode;
import org.sirix.node.interfaces.IStructNode;
import org.sirix.node.interfaces.IValNode;
import org.sirix.page.NamePage;
import org.sirix.page.UberPage;
import org.sirix.service.xml.shredder.EInsert;
import org.sirix.service.xml.shredder.EShredderCommit;
import org.sirix.service.xml.shredder.XMLShredder;
import org.sirix.settings.EFixed;
import org.sirix.utils.Compression;
import org.sirix.utils.IConstants;
import org.sirix.utils.NamePageHash;
import org.sirix.utils.XMLToken;

/**
 * <h1>WriteTransaction</h1>
 * 
 * <p>
 * Single-threaded instance of only write transaction per session.
 * </p>
 * 
 * <p>
 * All methods throw {@link NullPointerException}s in case of null values for reference peters.
 * </p>
 * 
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger, University of Konstanz
 */
public final class NodeWriteTrx extends AbsForwardingNodeReadTrx implements INodeWriteTrx {

  /** Determines movement after {@code attribute}- or {@code namespace}-insertions. */
  public enum EMove {
    /** Move to parent element node. */
    TOPARENT,

    /** Do not move. */
    NONE
  }

  /**
   * How is the Hash for this storage computed?
   */
  public enum EHashKind {
    /** Rolling hash, only nodes on ancestor axis are touched. */
    Rolling,
    /**
     * Postorder hash, all nodes on ancestor plus postorder are at least
     * read.
     */
    Postorder,
    /** No hash structure after all. */
    None;
  }

  /** MD5 hash-function. */
  private final HashFunction mHash = Hashing.md5();

  /** Prime for computing the hash. */
  private static final int PRIME = 77081;

  /** Maximum number of node modifications before auto commit. */
  private final int mMaxNodeCount;

  /** Modification counter. */
  private long mModificationCount;

  /** Hash kind of Structure. */
  private final EHashKind mHashKind;

  /** Scheduled executor service. */
  private final ScheduledExecutorService mPool = Executors.newScheduledThreadPool(Runtime.getRuntime()
    .availableProcessors());

  /** {@link NodeReadTrx} reference. */
  private final NodeReadTrx mNodeReadRtx;

  /** Determines if a bulk insert operation is done. */
  private boolean mBulkInsert;

  /**
   * Constructor.
   * 
   * @param pTransactionID
   *          ID of transaction
   * @param pSession
   *          the {@link session} instance this transaction is bound to
   * @param pPageWriteTransaction
   *          {@link IPageWriteTrx} to interact with the page layer
   * @param pMaxNodeCount
   *          maximum number of node modifications before auto commit
   * @param pTimeUnit
   *          unit of the number of the next param {@code pMaxTime}
   * @param pMaxTime
   *          maximum number of seconds before auto commit
   * @throws TTIOException
   *           if the reading of the props is failing
   * @throws TTUsageException
   *           if {@code pMaxNodeCount < 0} or {@code pMaxTime < 0}
   */
  NodeWriteTrx(@Nonnegative final long pTransactionID, @Nonnull final Session pSession,
    @Nonnull final IPageWriteTrx pPageWriteTransaction, @Nonnegative final int pMaxNodeCount,
    @Nonnull final TimeUnit pTimeUnit, @Nonnegative final int pMaxTime) throws TTIOException,
    TTUsageException {

    // Do not accept negative values.
    if ((pMaxNodeCount < 0) || (pMaxTime < 0)) {
      throw new TTUsageException("Negative arguments are not accepted.");
    }

    mNodeReadRtx = new NodeReadTrx(pSession, pTransactionID, pPageWriteTransaction);

    // Only auto commit by node modifications if it is more then 0.
    mMaxNodeCount = pMaxNodeCount;
    mModificationCount = 0L;

    if (pMaxTime > 0) {
      mPool.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
          try {
            commit();
          } catch (final AbsTTException e) {
            throw new IllegalStateException(e);
          }
        }
      }, pMaxTime, pMaxTime, pTimeUnit);
    }

    mHashKind = pSession.mResourceConfig.mHashKind;
  }

  @Override
  public synchronized INodeWriteTrx moveSubtreeToFirstChild(@Nonnegative final long pFromKey)
    throws AbsTTException, IllegalArgumentException {
    if (pFromKey < 0 || pFromKey > getMaxNodeKey()) {
      throw new IllegalArgumentException("Argument must be a valid node key!");
    }
    if (pFromKey == getNode().getNodeKey()) {
      throw new IllegalArgumentException("Can't move itself to right sibling of itself!");
    }

    final Optional<? extends INode> node = getPageTransaction().getNode(pFromKey);
    if (!node.isPresent()) {
      throw new IllegalStateException("Node to move must exist!");
    }

    final INode nodeToMove = node.get();

    if (nodeToMove instanceof IStructNode && getNode().getKind() == ENode.ELEMENT_KIND) {
      // Safe to cast (because IStructNode is a subtype of INode).
      checkAncestors(nodeToMove);
      checkAccessAndCommit();

      final ElementNode nodeAnchor = (ElementNode)getNode();
      if (nodeAnchor.getFirstChildKey() != nodeToMove.getNodeKey()) {
        final IStructNode toMove = (IStructNode)nodeToMove;
        // Adapt hashes.
        adaptHashesForMove(toMove);

        // Adapt pointers and merge sibling text nodes.
        adaptForMove(toMove, nodeAnchor, EInsertPos.ASFIRSTCHILD);

        mNodeReadRtx.setCurrentNode(toMove);
        adaptHashesWithAdd();
      }

      return this;
    } else {
      throw new TTUsageException(
        "Move is not allowed if moved node is not an ElementNode and the node isn't inserted at an element node!");
    }
  }

  @Override
  public synchronized INodeWriteTrx moveSubtreeToLeftSibling(@Nonnegative final long pFromKey)
    throws AbsTTException, IllegalArgumentException {
    if (getStructuralNode().hasLeftSibling()) {
      moveToLeftSibling();
      return moveSubtreeToRightSibling(pFromKey);
    } else {
      moveToParent();
      return moveSubtreeToFirstChild(pFromKey);
    }
  }

  @Override
  public synchronized INodeWriteTrx moveSubtreeToRightSibling(@Nonnegative final long pFromKey)
    throws AbsTTException {
    if (pFromKey < 0 || pFromKey > getMaxNodeKey()) {
      throw new IllegalArgumentException("Argument must be a valid node key!");
    }
    if (pFromKey == getNode().getNodeKey()) {
      throw new IllegalArgumentException("Can't move itself to first child of itself!");
    }

    final Optional<? extends INode> node = getPageTransaction().getNode(pFromKey);
    if (!node.isPresent()) {
      throw new IllegalStateException("Node to move must exist!");
    }

    final INode nodeToMove = node.get();

    if (nodeToMove instanceof IStructNode && getNode() instanceof IStructNode) {
      final IStructNode toMove = (IStructNode)nodeToMove;
      checkAncestors(toMove);
      checkAccessAndCommit();

      final IStructNode nodeAnchor = (IStructNode)getNode();
      if (nodeAnchor.getRightSiblingKey() != nodeToMove.getNodeKey()) {
        // Adapt hashes.
        adaptHashesForMove(toMove);

        // Adapt pointers and merge sibling text nodes.
        adaptForMove(toMove, nodeAnchor, EInsertPos.ASRIGHTSIBLING);
        mNodeReadRtx.setCurrentNode(getPageTransaction().getNode(nodeToMove.getNodeKey()).get());
        adaptHashesWithAdd();
      }
      return this;
    } else {
      throw new TTUsageException(
        "Move is not allowed if moved node is not an ElementNode or TextNode and the node isn't inserted at an ElementNode or TextNode!");
    }
  }

  /**
   * Adapt hashes for move operation ("remove" phase).
   * 
   * @param pNodeToMove
   *          node which implements {@link IStructNode} and is moved
   * @throws TTIOException
   *           if any I/O operation fails
   */
  private void adaptHashesForMove(@Nonnull final IStructNode pNodeToMove) throws TTIOException {
    assert pNodeToMove != null;
    mNodeReadRtx.setCurrentNode(pNodeToMove);
    adaptHashesWithRemove();
  }

  /**
   * Adapting everything for move operations.
   * 
   * @param pFromNode
   *          root {@link IStructNode} of the subtree to be moved
   * @param pToNode
   *          the {@link IStructNode} which is the anchor of the new
   *          subtree
   * @param pInsert
   *          determines if it has to be inserted as a first child or a
   *          right sibling
   * @throws AbsTTException
   *           if removing a node fails after merging text nodes
   */
  private void adaptForMove(@Nonnull final IStructNode pFromNode, @Nonnull final IStructNode pToNode,
    @Nonnull final EInsertPos pInsert) throws AbsTTException {
    assert pFromNode != null;
    assert pToNode != null;
    assert pInsert != null;

    // Modify nodes where the subtree has been moved from.
    // ==============================================================================
    final IStructNode parent =
      (IStructNode)getPageTransaction().prepareNodeForModification(pFromNode.getParentKey());
    switch (pInsert) {
    case ASRIGHTSIBLING:
      if (pFromNode.getParentKey() != pToNode.getParentKey()) {
        parent.decrementChildCount();
      }
      break;
    case ASFIRSTCHILD:
      if (pFromNode.getParentKey() != pToNode.getNodeKey()) {
        parent.decrementChildCount();
      }
      break;
    case ASNONSTRUCTURAL:
      // Do not decrement child count.
      break;
    }

    // Adapt first child key of former parent.
    if (parent.getFirstChildKey() == pFromNode.getNodeKey()) {
      parent.setFirstChildKey(pFromNode.getRightSiblingKey());
    }
    getPageTransaction().finishNodeModification(parent);

    // Adapt left sibling key of former right sibling.
    if (pFromNode.hasRightSibling()) {
      final IStructNode rightSibling =
        (IStructNode)getPageTransaction().prepareNodeForModification(pFromNode.getRightSiblingKey());
      rightSibling.setLeftSiblingKey(pFromNode.getLeftSiblingKey());
      getPageTransaction().finishNodeModification(rightSibling);
    }

    // Adapt right sibling key of former left sibling.
    if (pFromNode.hasLeftSibling()) {
      final IStructNode leftSibling =
        (IStructNode)getPageTransaction().prepareNodeForModification(pFromNode.getLeftSiblingKey());
      leftSibling.setRightSiblingKey(pFromNode.getRightSiblingKey());
      getPageTransaction().finishNodeModification(leftSibling);
    }

    // Merge text nodes.
    if (pFromNode.hasLeftSibling() && pFromNode.hasRightSibling()) {
      moveTo(pFromNode.getLeftSiblingKey());
      if (getNode() != null && getNode().getKind() == ENode.TEXT_KIND) {
        final StringBuilder builder = new StringBuilder(getValueOfCurrentNode()).append(" ");
        moveTo(pFromNode.getRightSiblingKey());
        if (getNode() != null && getNode().getKind() == ENode.TEXT_KIND) {
          builder.append(getValueOfCurrentNode());
          if (pFromNode.getRightSiblingKey() == pToNode.getNodeKey()) {
            moveTo(pFromNode.getLeftSiblingKey());
            if (getStructuralNode().hasLeftSibling()) {
              final IStructNode leftSibling =
                (IStructNode)getPageTransaction().prepareNodeForModification(
                  getStructuralNode().getLeftSiblingKey());
              leftSibling.setRightSiblingKey(pFromNode.getRightSiblingKey());
              getPageTransaction().finishNodeModification(leftSibling);
            }
            final long leftSiblingKey =
              getStructuralNode().hasLeftSibling() == true ? getStructuralNode().getLeftSiblingKey()
                : getNode().getNodeKey();
            moveTo(pFromNode.getRightSiblingKey());
            final IStructNode rightSibling =
              (IStructNode)getPageTransaction().prepareNodeForModification(getNode().getNodeKey());
            rightSibling.setLeftSiblingKey(leftSiblingKey);
            getPageTransaction().finishNodeModification(rightSibling);
            moveTo(pFromNode.getLeftSiblingKey());
            remove();
            moveTo(pFromNode.getRightSiblingKey());
          } else {
            if (getStructuralNode().hasRightSibling()) {
              final IStructNode rightSibling =
                (IStructNode)getPageTransaction().prepareNodeForModification(
                  getStructuralNode().getRightSiblingKey());
              rightSibling.setLeftSiblingKey(pFromNode.getLeftSiblingKey());
              getPageTransaction().finishNodeModification(rightSibling);
            }
            final long rightSiblingKey =
              getStructuralNode().hasRightSibling() == true ? getStructuralNode().getRightSiblingKey()
                : getNode().getNodeKey();
            moveTo(pFromNode.getLeftSiblingKey());
            final IStructNode leftSibling =
              (IStructNode)getPageTransaction().prepareNodeForModification(getNode().getNodeKey());
            leftSibling.setRightSiblingKey(rightSiblingKey);
            getPageTransaction().finishNodeModification(leftSibling);
            moveTo(pFromNode.getRightSiblingKey());
            remove();
            moveTo(pFromNode.getLeftSiblingKey());
          }
          setValue(builder.toString());
        }
      }
    }

    // Modify nodes where the subtree has been moved to.
    // ==============================================================================
    pInsert.processMove(pFromNode, pToNode, this);
  }

  @Override
  public synchronized INodeWriteTrx insertElementAsFirstChild(@Nonnull final QName pQName)
    throws AbsTTException {
    if (!XMLToken.isValidQName(checkNotNull(pQName))) {
      throw new IllegalArgumentException("The QName is not valid!");
    }
    final ENode kind = getNode().getKind();
    if (kind == ENode.ELEMENT_KIND || kind == ENode.ROOT_KIND) {
      checkAccessAndCommit();

      final long parentKey = getNode().getNodeKey();
      final long leftSibKey = EFixed.NULL_NODE_KEY.getStandardProperty();
      final long rightSibKey = ((IStructNode)getNode()).getFirstChildKey();
      final ElementNode node = createElementNode(parentKey, leftSibKey, rightSibKey, 0, pQName);

      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASFIRSTCHILD);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();

      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an ElementNode!");
    }
  }

  @Override
  public synchronized INodeWriteTrx insertElementAsLeftSibling(@Nonnull final QName pQName)
    throws AbsTTException {
    if (!XMLToken.isValidQName(checkNotNull(pQName))) {
      throw new IllegalArgumentException("The QName is not valid!");
    }
    if (getNode() instanceof IStructNode) {
      checkAccessAndCommit();

      final long parentKey = getNode().getParentKey();
      final long leftSibKey = ((IStructNode)getNode()).getLeftSiblingKey();
      final long rightSibKey = getNode().getNodeKey();
      final ElementNode node = createElementNode(parentKey, leftSibKey, rightSibKey, 0, pQName);

      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASLEFTSIBLING);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();

      return this;
    } else {
      throw new TTUsageException(
        "Insert is not allowed if current node is not an StructuralNode (either Text or Element)!");
    }
  }

  @Override
  public synchronized INodeWriteTrx insertElementAsRightSibling(@Nonnull final QName pQName)
    throws AbsTTException {
    if (!XMLToken.isValidQName(checkNotNull(pQName))) {
      throw new IllegalArgumentException("The QName is not valid!");
    }
    if (getNode() instanceof IStructNode) {
      checkAccessAndCommit();

      final long parentKey = getNode().getParentKey();
      final long leftSibKey = getNode().getNodeKey();
      final long rightSibKey = ((IStructNode)getNode()).getRightSiblingKey();
      final ElementNode node = createElementNode(parentKey, leftSibKey, rightSibKey, 0, pQName);

      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASRIGHTSIBLING);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();

      return this;
    } else {
      throw new TTUsageException(
        "Insert is not allowed if current node is not an StructuralNode (either Text or Element)!");
    }
  }

  @Override
  public INodeWriteTrx insertSubtree(@Nonnull final XMLEventReader pReader, @Nonnull final EInsert pInsert)
    throws AbsTTException {
    mBulkInsert = true;
    long nodeKey = getNode().getNodeKey();
    final XMLShredder shredder = new XMLShredder(this, pReader, pInsert);
    shredder.call();
    moveTo(nodeKey);
    switch (pInsert) {
    case ASFIRSTCHILD:
      moveToFirstChild();
      break;
    case ASRIGHTSIBLING:
      moveToRightSibling();
      break;
    }
    nodeKey = getNode().getNodeKey();
    mBulkInsert = false;
    postOrderTraversalHashes();
    final INode startNode = getNode();
    moveToParent();
    while (getNode().hasParent()) {
      moveToParent();
      addAncestorHash(startNode);
    }
    moveTo(nodeKey);
    return this;
  }

  @Override
  public synchronized INodeWriteTrx insertTextAsFirstChild(@Nonnull final String pValue)
    throws AbsTTException {
    checkNotNull(pValue);
    if (getNode() instanceof IStructNode && getNode().getKind() != ENode.ROOT_KIND) {
      checkAccessAndCommit();

      final long parentKey = getNode().getNodeKey();
      final long leftSibKey = EFixed.NULL_NODE_KEY.getStandardProperty();
      final long rightSibKey = ((IStructNode)getNode()).getFirstChildKey();

      // Update value in case of adjacent text nodes.
      if (moveTo(rightSibKey)) {
        if (getNode().getKind() == ENode.TEXT_KIND) {
          setValue(new StringBuilder(pValue).append(" ").append(getValueOfCurrentNode()).toString());
          adaptHashedWithUpdate(getNode().getHash());
          return this;
        }
        moveTo(parentKey);
      }

      // Insert new text node if no adjacent text nodes are found.
      final byte[] value = getBytes(pValue);
      final TextNode node =
        createTextNode(parentKey, leftSibKey, rightSibKey, value, mNodeReadRtx.mSession.mResourceConfig
          .isCompression());

      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASFIRSTCHILD);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();

      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an ElementNode or TextNode!");
    }
  }

  @Override
  public INodeWriteTrx insertTextAsLeftSibling(final String pValue) throws AbsTTException {
    checkNotNull(pValue);
    if (getNode() instanceof IStructNode) {
      checkAccessAndCommit();

      final long parentKey = getNode().getParentKey();
      final long leftSibKey = ((IStructNode)getNode()).getLeftSiblingKey();
      final long rightSibKey = getNode().getNodeKey();

      // Update value in case of adjacent text nodes.
      final StringBuilder builder = new StringBuilder();
      if (getNode().getKind() == ENode.TEXT_KIND) {
        builder.append(pValue).append(" ");
      }

      builder.append(getValueOfCurrentNode());

      if (!pValue.equals(builder.toString())) {
        setValue(builder.toString());
        return this;
      }
      if (moveTo(leftSibKey)) {
        final StringBuilder value = new StringBuilder();
        if (getNode().getKind() == ENode.TEXT_KIND) {
          value.append(getValueOfCurrentNode()).append(" ").append(builder);
        }
        if (!pValue.equals(value.toString())) {
          setValue(value.toString());
          return this;
        }
      }

      // Insert new text node if no adjacent text nodes are found.
      moveTo(rightSibKey);
      final byte[] value = getBytes(builder.toString());
      final TextNode node =
        createTextNode(parentKey, leftSibKey, rightSibKey, value, mNodeReadRtx.mSession.mResourceConfig
          .isCompression());
      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASLEFTSIBLING);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();
      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an Element- or Text-node!");
    }
  }

  @Override
  public synchronized INodeWriteTrx insertTextAsRightSibling(@Nonnull final String pValue)
    throws AbsTTException {
    checkNotNull(pValue);
    if (getNode() instanceof IStructNode) {
      checkAccessAndCommit();

      final long parentKey = getNode().getParentKey();
      final long leftSibKey = getNode().getNodeKey();
      final long rightSibKey = ((IStructNode)getNode()).getRightSiblingKey();

      // Update value in case of adjacent text nodes.
      final StringBuilder builder = new StringBuilder();
      if (getNode().getKind() == ENode.TEXT_KIND) {
        builder.append(getValueOfCurrentNode()).append(" ");
      }
      builder.append(pValue);
      if (!pValue.equals(builder.toString())) {
        setValue(builder.toString());
        return this;
      }
      if (moveTo(rightSibKey)) {
        if (getNode().getKind() == ENode.TEXT_KIND) {
          builder.append(" ").append(getValueOfCurrentNode());
        }
        if (!pValue.equals(builder.toString())) {
          setValue(builder.toString());
          return this;
        }
      }

      // Insert new text node if no adjacent text nodes are found.
      moveTo(leftSibKey);
      final byte[] value = getBytes(builder.toString());
      final TextNode node =
        createTextNode(parentKey, leftSibKey, rightSibKey, value, mNodeReadRtx.mSession.mResourceConfig
          .isCompression());
      mNodeReadRtx.setCurrentNode(node);
      adaptForInsert(node, EInsertPos.ASRIGHTSIBLING);
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();
      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an Element- or Text-node!");
    }
  }

  /**
   * Get a byte-array from a value.
   * 
   * @param pValue
   *          the value
   * @return byte-array representation of {@code pValue}
   */
  private byte[] getBytes(final String pValue) {
    return pValue.getBytes(IConstants.DEFAULT_ENCODING);
  }

  @Override
  public synchronized INodeWriteTrx
    insertAttribute(@Nonnull final QName pQName, @Nonnull final String pValue) throws AbsTTException {
    return insertAttribute(pQName, pValue, EMove.NONE);
  }

  @Override
  public synchronized INodeWriteTrx insertAttribute(@Nonnull final QName pQName,
    @Nonnull final String pValue, @Nonnull final EMove pMove) throws AbsTTException {
    checkNotNull(pValue);
    if (!XMLToken.isValidQName(checkNotNull(pQName))) {
      throw new IllegalArgumentException("The QName is not valid!");
    }
    if (getNode().getKind() == ENode.ELEMENT_KIND) {
      checkAccessAndCommit();

      /*
       * Update value in case of the same attribute name is found but the attribute to insert has a
       * different value (otherwise an exception is thrown because of a duplicate attribute which
       * would otherwise be inserted!).
       */
      final Optional<Long> attKey =
        ((ElementNode)getNode()).getAttributeKeyByName(NamePageHash.generateHashForString(PageWriteTrx
          .buildName(pQName)));
      if (attKey.isPresent()) {
        moveTo(attKey.get());
        final QName qName = getQNameOfCurrentNode();
        if (pQName.equals(qName) && pQName.getPrefix().equals(qName.getPrefix())) {
          if (!getValueOfCurrentNode().equals(pValue)) {
            setValue(pValue);
          } else {
            throw new TTUsageException("Duplicate attribute!");
          }
        }
        moveToParent();
      }
      final byte[] value = getBytes(pValue);
      final long elementKey = getNode().getNodeKey();
      final AttributeNode node = createAttributeNode(elementKey, pQName, value);

      final INode parentNode = getPageTransaction().prepareNodeForModification(node.getParentKey());
      ((ElementNode)parentNode).insertAttribute(node.getNodeKey(), node.getNameKey());
      getPageTransaction().finishNodeModification(parentNode);

      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();
      if (pMove == EMove.TOPARENT) {
        moveToParent();
      }
      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an ElementNode!");
    }
  }

  @Override
  public synchronized INodeWriteTrx insertNamespace(@Nonnull final QName pQName) throws AbsTTException {
    return insertNamespace(pQName, EMove.NONE);
  }

  @Override
  public synchronized INodeWriteTrx insertNamespace(@Nonnull final QName pQName, @Nonnull final EMove pMove)
    throws AbsTTException {
    if (!XMLToken.isValidQName(checkNotNull(pQName))) {
      throw new IllegalArgumentException("The QName is not valid!");
    }
    if (getNode().getKind() == ENode.ELEMENT_KIND) {
      checkAccessAndCommit();

      for (int i = 0, namespCount = ((ElementNode)getNode()).getNamespaceCount(); i < namespCount; i++) {
        moveToNamespace(i);
        final QName qName = getQNameOfCurrentNode();
        if (pQName.getPrefix().equals(qName.getPrefix())) {
          throw new TTUsageException("Duplicate namespace!");
        }
        moveToParent();
      }

      final int uriKey = getPageTransaction().createNameKey(pQName.getNamespaceURI(), ENode.NAMESPACE_KIND);
      final int prefixKey = getPageTransaction().createNameKey(pQName.getPrefix(), ENode.NAMESPACE_KIND);
      final long elementKey = getNode().getNodeKey();

      final NamespaceNode node = createNamespaceNode(elementKey, uriKey, prefixKey);

      final INode parentNode = getPageTransaction().prepareNodeForModification(node.getParentKey());
      ((ElementNode)parentNode).insertNamespace(node.getNodeKey());
      getPageTransaction().finishNodeModification(parentNode);

      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithAdd();
      if (pMove == EMove.TOPARENT) {
        moveToParent();
      }
      return this;
    } else {
      throw new TTUsageException("Insert is not allowed if current node is not an ElementNode!");
    }
  }

  /**
   * Check ancestors of current node.
   * 
   * @throws AssertionError
   *           if pItem is null
   * @throws IllegalStateException
   *           if one of the ancestors is the node/subtree rooted at the node to move
   */
  private void checkAncestors(final INode pItem) {
    assert pItem != null;
    final INode item = getNode();
    while (getNode().hasParent()) {
      moveToParent();
      if (getNode().getNodeKey() == pItem.getNodeKey()) {
        throw new IllegalStateException("Moving one of the ancestor nodes is not permitted!");
      }
    }
    moveTo(item.getNodeKey());
  }

  @Override
  public synchronized void remove() throws AbsTTException {
    checkAccessAndCommit();
    if (getNode().getKind() == ENode.ROOT_KIND) {
      throw new TTUsageException("Document root can not be removed.");
    } else if (getNode() instanceof IStructNode) {
      final IStructNode node = (IStructNode)getNode();

      // Remove subtree.
      for (final IAxis axis = new DescendantAxis(this); axis.hasNext();) {
        axis.next();
        final IStructNode nodeToDelete = axis.getTransaction().getStructuralNode();
        if (nodeToDelete.getKind() == ENode.ELEMENT_KIND) {
          final ElementNode element = (ElementNode)nodeToDelete;
          removeName();
          final int attCount = element.getAttributeCount();
          for (int i = 0; i < attCount; i++) {
            moveToAttribute(i);
            removeName();
            getPageTransaction().removeNode(getNode());
            moveToParent();
          }
          final int nspCount = element.getNamespaceCount();
          for (int i = 0; i < nspCount; i++) {
            moveToNamespace(i);
            removeName();
            getPageTransaction().removeNode(getNode());
            moveToParent();
          }
        }
        getPageTransaction().removeNode(nodeToDelete);
      }
      mNodeReadRtx.setCurrentNode(node);
      adaptHashesWithRemove();
      adaptForRemove(node);
      mNodeReadRtx.setCurrentNode(node);
      if (node.getKind() == ENode.ELEMENT_KIND) {
        removeName();
      }

      // Set current node (don't remove the moveTo(long) inside the if-clause which is needed because
      // of text merges.
      if (node.hasRightSibling() && moveTo(node.getRightSiblingKey())) {
      } else if (node.hasLeftSibling()) {
        moveTo(node.getLeftSiblingKey());
      } else {
        moveTo(node.getParentKey());
      }
    } else if (getNode().getKind() == ENode.ATTRIBUTE_KIND) {
      final INode node = getNode();

      final ElementNode parent =
        (ElementNode)getPageTransaction().prepareNodeForModification(node.getParentKey());
      parent.removeAttribute(node.getNodeKey());
      getPageTransaction().finishNodeModification(parent);
      adaptHashesWithRemove();
      getPageTransaction().removeNode(node);
      removeName();
      moveToParent();
    } else if (getNode().getKind() == ENode.NAMESPACE_KIND) {
      final INode node = getNode();

      final ElementNode parent =
        (ElementNode)getPageTransaction().prepareNodeForModification(node.getParentKey());
      parent.removeNamespace(node.getNodeKey());
      getPageTransaction().finishNodeModification(parent);
      adaptHashesWithRemove();
      getPageTransaction().removeNode(node);
      removeName();
      moveToParent();
    }
  }

  /**
   * Remove a name from the {@link NamePage} reference.
   * 
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void removeName() throws TTIOException {
    assert getNode() instanceof INameNode;
    final INameNode node = ((INameNode)getNode());
    final ENode nodeKind = node.getKind();
    final NamePage page =
      ((NamePage)getPageTransaction().getActualRevisionRootPage().getNamePageReference().getPage());
    page.removeName(node.getNameKey(), nodeKind);
    page.removeName(node.getURIKey(), nodeKind);
  }

  @Override
  public synchronized void setQName(@Nonnull final QName pName) throws AbsTTException {
    checkNotNull(pName);
    if (getNode() instanceof INameNode) {
      if (!getQNameOfCurrentNode().equals(pName)) {
        checkAccessAndCommit();
        final long oldHash = mNodeReadRtx.getNode().hashCode();
        removeName();
        final INameNode node =
          (INameNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        node.setNameKey(getPageTransaction().createNameKey(PageWriteTrx.buildName(pName),
          mNodeReadRtx.getNode().getKind()));
        node.setURIKey(getPageTransaction().createNameKey(pName.getNamespaceURI(),
          mNodeReadRtx.getNode().getKind()));
        getPageTransaction().finishNodeModification(node);

        mNodeReadRtx.setCurrentNode(node);
        adaptHashedWithUpdate(oldHash);
      }
    } else {
      throw new TTUsageException(
        "setQName is not allowed if current node is not an INameNode implementation!");
    }
  }

  @Override
  public synchronized void setURI(@Nonnull final String pUri) throws AbsTTException {
    checkNotNull(pUri);
    if (getNode() instanceof INameNode) {
      if (!getValueOfCurrentNode().equals(pUri)) {
        checkAccessAndCommit();
        final long oldHash = mNodeReadRtx.getNode().hashCode();
        final NamePage page =
          (NamePage)getPageTransaction().getActualRevisionRootPage().getNamePageReference().getPage();
        page.removeName(NamePageHash.generateHashForString(getValueOfCurrentNode()), ENode.NAMESPACE_KIND);
        final INameNode node =
          (INameNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        node.setURIKey(getPageTransaction().createNameKey(pUri, mNodeReadRtx.getNode().getKind()));
        getPageTransaction().finishNodeModification(node);

        mNodeReadRtx.setCurrentNode(node);
        adaptHashedWithUpdate(oldHash);
      }
    } else {
      throw new TTUsageException("setURI is not allowed if current node is not an INameNode implementation!");
    }
  }

  @Override
  public synchronized void setValue(@Nonnull final String pValue) throws AbsTTException {
    checkNotNull(pValue);
    if (getNode() instanceof IValNode) {
      checkAccessAndCommit();
      final long oldHash = mNodeReadRtx.getNode().hashCode();

      final IValNode node =
        (IValNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
      node.setValue(getBytes(pValue));
      getPageTransaction().finishNodeModification(node);

      mNodeReadRtx.setCurrentNode(node);
      adaptHashedWithUpdate(oldHash);
    } else {
      throw new TTUsageException("SetValue is not allowed if current node is not an IValNode implementation!");
    }
  }

  @Override
  public void revertTo(@Nonnegative final long pRevision) throws TTUsageException, TTIOException {
    mNodeReadRtx.assertNotClosed();
    mNodeReadRtx.mSession.assertAccess(pRevision);

    // Close current page transaction.
    getPageTransaction().close();

    // Reset internal transaction state to new uber page.
    mNodeReadRtx.setPageReadTransaction(mNodeReadRtx.mSession.createPageWriteTransaction(getTransactionID(),
      pRevision, getRevisionNumber() - 1));

    // Reset modification counter.
    mModificationCount = 0L;
    moveToDocumentRoot();
  }

  @Override
  public synchronized void commit() throws AbsTTException {
    mNodeReadRtx.assertNotClosed();

    // Assert that the DocumentNode has no more than one child node (the root node).
    final long nodeKey = mNodeReadRtx.getNode().getNodeKey();
    moveToDocumentRoot();
    final DocumentRootNode document = (DocumentRootNode)mNodeReadRtx.getNode();
    if (document.getChildCount() > 1) {
      moveTo(nodeKey);
      throw new IllegalStateException("DocumentRootNode may not have more than one child node!");
    }
    moveTo(nodeKey);

    // If it is the first commited revision and eventual consistency option specified.
    if (mNodeReadRtx.mSession.mResourceConfig.mConsistency == EConsistency.EVENTUAL
      && getPageTransaction().getUberPage().isBootstrap() && mModificationCount > 0) {
      postOrderTraversalHashes();
    }

    // Commit uber page.
    final UberPage uberPage = getPageTransaction().commit();

    // Remember succesfully committed uber page in session state.
    mNodeReadRtx.mSession.setLastCommittedUberPage(uberPage);

    // Reset modification counter.
    mModificationCount = 0L;

    // Close current page transaction.
    getPageTransaction().close();

    // Reset page transaction state to new uber page.
    mNodeReadRtx.setPageReadTransaction(mNodeReadRtx.mSession.createPageWriteTransaction(getTransactionID(),
      getRevisionNumber(), getRevisionNumber()));
  }

  /**
   * Modifying hashes in a postorder-traversal.
   * 
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void postOrderTraversalHashes() throws TTIOException {
    for (final IAxis axis = new PostOrderAxis(this); axis.hasNext();) {
      axis.next();
      final IStructNode node = getStructuralNode();
      if (node.getKind() == ENode.ELEMENT_KIND) {
        final ElementNode element = (ElementNode)node;
        for (int i = 0, nspCount = element.getNamespaceCount(); i < nspCount; i++) {
          moveToNamespace(i);
          addHash();
          moveToParent();
        }
        for (int i = 0, attCount = element.getAttributeCount(); i < attCount; i++) {
          moveToAttribute(i);
          addHash();
          moveToParent();
        }
      }
      addHash();
    }
  }

  /** Add a hash. */
  private void addAncestorHash(final INode pStartNode) throws TTIOException {
    switch (mHashKind) {
    case Rolling:
      long hashToAdd = mHash.hashLong(pStartNode.hashCode()).asLong();
      INode node = getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
      node.setHash(node.getHash() + hashToAdd * PRIME);
      if (pStartNode instanceof IStructNode) {
        ((IStructNode)node).setDescendantCount(((IStructNode)node).getDescendantCount()
          + ((IStructNode)pStartNode).getDescendantCount() + 1);
      }
      getPageTransaction().finishNodeModification(node);
      break;
    case Postorder:
      break;
    }
  }

  /** Add a hash. */
  private void addHash() throws TTIOException {
    switch (mHashKind) {
    case Rolling:
      // Setup.
      final INode startNode = getNode();
      final long oldDescendantCount = getStructuralNode().getDescendantCount();
      final long descendantCount = oldDescendantCount == 0 ? 1 : oldDescendantCount + 1;

      // Set start node.
      long hashToAdd = mHash.hashLong(startNode.hashCode()).asLong();
      INode node = getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
      node.setHash(hashToAdd);
      getPageTransaction().finishNodeModification(node);

      // Set parent node.
      if (startNode.hasParent()) {
        moveToParent();
        node = getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        node.setHash(node.getHash() + hashToAdd * PRIME);
        setAddDescendants(startNode, node, descendantCount);
        getPageTransaction().finishNodeModification(node);
      }

      mNodeReadRtx.setCurrentNode(startNode);
      break;
    case Postorder:
      postorderAdd();
      break;
    default:
    }
  }

  @Override
  public synchronized void abort() throws TTIOException {
    mNodeReadRtx.assertNotClosed();

    // Reset modification counter.
    mModificationCount = 0L;

    getPageTransaction().close();

    long revisionToSet = 0;
    if (!getPageTransaction().getUberPage().isBootstrap()) {
      revisionToSet = getRevisionNumber() - 1;
    }

    // Reset page transaction to last committed uber page.
    mNodeReadRtx.setPageReadTransaction(mNodeReadRtx.mSession.createPageWriteTransaction(getTransactionID(),
      revisionToSet, revisionToSet));
  }

  @Override
  public synchronized void close() throws AbsTTException {
    if (!isClosed()) {
      // Make sure to commit all dirty data.
      if (mModificationCount > 0) {
        throw new TTUsageException("Must commit/abort transaction first");
      }
      // Release all state immediately.
      getPageTransaction().close();
      mNodeReadRtx.mSession.closeWriteTransaction(getTransactionID());
      mNodeReadRtx.setPageReadTransaction(null);
      mNodeReadRtx.setCurrentNode(null);
      // Remember that we are closed.
      mNodeReadRtx.setClosed();
      // Shutdown pool.
      mPool.shutdown();
      try {
        mPool.awaitTermination(5, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        throw new TTThreadedException(e);
      }
    }
  }

  /**
   * Checking write access and intermediate commit.
   * 
   * @throws AbsTTException
   *           if anything weird happens
   */
  private void checkAccessAndCommit() throws AbsTTException {
    mNodeReadRtx.assertNotClosed();
    mModificationCount++;
    intermediateCommitIfRequired();
  }

  // ////////////////////////////////////////////////////////////
  // insert operation
  // ////////////////////////////////////////////////////////////

  /**
   * Adapting everything for insert operations.
   * 
   * @param pNewNode
   *          pointer of the new node to be inserted
   * @param pInsert
   *          determines the position where to insert
   * @throws TTIOException
   *           if anything weird happens
   */
  private void adaptForInsert(@Nonnull final INode pNewNode, @Nonnull final EInsertPos pInsert)
    throws TTIOException {
    assert pNewNode != null;
    assert pInsert != null;

    if (pNewNode instanceof IStructNode) {
      final IStructNode strucNode = (IStructNode)pNewNode;
      final IStructNode parent =
        (IStructNode)getPageTransaction().prepareNodeForModification(pNewNode.getParentKey());
      parent.incrementChildCount();
      if (pInsert == EInsertPos.ASFIRSTCHILD) {
        parent.setFirstChildKey(pNewNode.getNodeKey());
      }
      getPageTransaction().finishNodeModification(parent);

      if (strucNode.hasRightSibling()) {
        final IStructNode rightSiblingNode =
          (IStructNode)getPageTransaction().prepareNodeForModification(strucNode.getRightSiblingKey());
        rightSiblingNode.setLeftSiblingKey(pNewNode.getNodeKey());
        getPageTransaction().finishNodeModification(rightSiblingNode);
      }
      if (strucNode.hasLeftSibling()) {
        final IStructNode leftSiblingNode =
          (IStructNode)getPageTransaction().prepareNodeForModification(strucNode.getLeftSiblingKey());
        leftSiblingNode.setRightSiblingKey(pNewNode.getNodeKey());
        getPageTransaction().finishNodeModification(leftSiblingNode);
      }
    }
  }

  // ////////////////////////////////////////////////////////////
  // end of insert operation
  // ////////////////////////////////////////////////////////////

  // ////////////////////////////////////////////////////////////
  // remove operation
  // ////////////////////////////////////////////////////////////

  /**
   * Adapting everything for remove operations.
   * 
   * @param pOldNode
   *          pointer of the old node to be replaced
   * @throws AbsTTException
   *           if anything weird happens
   */
  private void adaptForRemove(@Nonnull final IStructNode pOldNode) throws AbsTTException {
    assert pOldNode != null;

    // Concatenate neighbor text nodes if they exist (the right sibling is deleted afterwards).
    boolean concatenated = false;
    if (pOldNode.hasLeftSibling() && pOldNode.hasRightSibling() && moveTo(pOldNode.getRightSiblingKey())
      && getNode().getKind() == ENode.TEXT_KIND && moveTo(pOldNode.getLeftSiblingKey())
      && getNode().getKind() == ENode.TEXT_KIND) {
      final StringBuilder builder = new StringBuilder(getValueOfCurrentNode()).append(" ");
      moveTo(pOldNode.getRightSiblingKey());
      builder.append(getValueOfCurrentNode());
      moveTo(pOldNode.getLeftSiblingKey());
      setValue(builder.toString());
      concatenated = true;
    }

    // Adapt left sibling node if there is one.
    if (pOldNode.hasLeftSibling()) {
      final IStructNode leftSibling =
        (IStructNode)getPageTransaction().prepareNodeForModification(pOldNode.getLeftSiblingKey());
      if (concatenated) {
        moveTo(pOldNode.getRightSiblingKey());
        leftSibling.setRightSiblingKey(((IStructNode)getNode()).getRightSiblingKey());
      } else {
        leftSibling.setRightSiblingKey(pOldNode.getRightSiblingKey());
      }
      getPageTransaction().finishNodeModification(leftSibling);
    }

    // Adapt right sibling node if there is one.
    if (pOldNode.hasRightSibling()) {
      IStructNode rightSibling;
      if (concatenated) {
        moveTo(pOldNode.getRightSiblingKey());
        moveTo(getStructuralNode().getRightSiblingKey());
        rightSibling =
          (IStructNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        rightSibling.setLeftSiblingKey(pOldNode.getLeftSiblingKey());
      } else {
        rightSibling =
          (IStructNode)getPageTransaction().prepareNodeForModification(pOldNode.getRightSiblingKey());
        rightSibling.setLeftSiblingKey(pOldNode.getLeftSiblingKey());
      }
      getPageTransaction().finishNodeModification(rightSibling);
    }

    // Adapt parent, if node has now left sibling it is a first child.
    IStructNode parent =
      (IStructNode)getPageTransaction().prepareNodeForModification(pOldNode.getParentKey());
    if (!pOldNode.hasLeftSibling()) {
      parent.setFirstChildKey(pOldNode.getRightSiblingKey());
    }
    parent.decrementChildCount();
    if (concatenated) {
      if (mNodeReadRtx.mSession.mResourceConfig.mConsistency != EConsistency.EVENTUAL
        || !getPageTransaction().getUberPage().isBootstrap()) {
        parent.decrementDescendantCount();
      }
      parent.decrementChildCount();
    }
    getPageTransaction().finishNodeModification(parent);
    if (concatenated
      && (mNodeReadRtx.mSession.mResourceConfig.mConsistency != EConsistency.EVENTUAL || !getPageTransaction()
        .getUberPage().isBootstrap())) {
      // Adjust descendant count.
      moveTo(parent.getNodeKey());
      while (parent.hasParent()) {
        moveToParent();
        final IStructNode ancestor =
          (IStructNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        ancestor.decrementDescendantCount();
        getPageTransaction().finishNodeModification(ancestor);
        parent = ancestor;
      }
    }

    if (pOldNode.getKind() == ENode.ELEMENT_KIND) {
      // Removing attributes.
      for (int i = 0; i < ((ElementNode)pOldNode).getAttributeCount(); i++) {
        moveTo(((ElementNode)pOldNode).getAttributeKey(i));
        getPageTransaction().removeNode(mNodeReadRtx.getNode());
      }
      // Removing namespaces.
      moveTo(pOldNode.getNodeKey());
      for (int i = 0; i < ((ElementNode)pOldNode).getNamespaceCount(); i++) {
        moveTo(((ElementNode)pOldNode).getNamespaceKey(i));
        getPageTransaction().removeNode(mNodeReadRtx.getNode());
      }
    }

    // Remove right sibling text node if text nodes have been concatenated/merged.
    if (concatenated) {
      moveTo(pOldNode.getRightSiblingKey());
      getPageTransaction().removeNode(mNodeReadRtx.getNode());
    }

    // Remove old node.
    moveTo(pOldNode.getNodeKey());
    getPageTransaction().removeNode(pOldNode);
  }

  // ////////////////////////////////////////////////////////////
  // end of remove operation
  // ////////////////////////////////////////////////////////////

  // ////////////////////////////////////////////////////////////
  // start of node creations
  // ////////////////////////////////////////////////////////////

  /**
   * Create an {@link ElementNode}.
   * 
   * @param pParentKey
   *          parent node key
   * @param pLeftSibKey
   *          left sibling key
   * @param pRightSibKey
   *          right sibling key
   * @param pHash
   *          hash value associated with the node
   * @param pName
   *          {@link QName} of the node
   * @return the created node
   * @throws TTIOException
   *           if an I/O error occurs
   */
  ElementNode createElementNode(@Nonnegative final long pParentKey, @Nonnegative final long pLeftSibKey,
    final long pRightSibKey, final long pHash, @Nonnull final QName pName) throws TTIOException {
    final IPageWriteTrx pageTransaction = getPageTransaction();
    final int nameKey = pageTransaction.createNameKey(PageWriteTrx.buildName(pName), ENode.ELEMENT_KIND);
    final int namespaceKey = pageTransaction.createNameKey(pName.getNamespaceURI(), ENode.NAMESPACE_KIND);

    final NodeDelegate nodeDel =
      new NodeDelegate(pageTransaction.getActualRevisionRootPage().getMaxNodeKey() + 1, pParentKey, 0);
    final StructNodeDelegate structDel =
      new StructNodeDelegate(nodeDel, EFixed.NULL_NODE_KEY.getStandardProperty(), pRightSibKey, pLeftSibKey,
        0, 0);
    final NameNodeDelegate nameDel = new NameNodeDelegate(nodeDel, nameKey, namespaceKey);

    return (ElementNode)pageTransaction.createNode(new ElementNode(nodeDel, structDel, nameDel,
      new ArrayList<Long>(), HashBiMap.<Integer, Long> create(), new ArrayList<Long>()));
  }

  /**
   * Create a {@link TextNode}.
   * 
   * @param pParentKey
   *          parent node key
   * @param pLeftSibKey
   *          left sibling key
   * @param pRightSibKey
   *          right sibling key
   * @param pValue
   *          value of the node
   * @param pIsCompressed
   *          determines if the value should be compressed or not
   * @return the created node
   * @throws TTIOException
   *           if an I/O error occurs
   */
  TextNode createTextNode(final long pParentKey, final long pLeftSibKey, final long pRightSibKey,
    final byte[] pValue, final boolean pIsCompressed) throws TTIOException {
    final IPageWriteTrx pageTransaction = getPageTransaction();
    final NodeDelegate nodeDel =
      new NodeDelegate(pageTransaction.getActualRevisionRootPage().getMaxNodeKey() + 1, pParentKey, 0);
    final boolean compression = pIsCompressed && pValue.length > 10;
    final byte[] value = compression ? Compression.compress(pValue, Deflater.HUFFMAN_ONLY) : pValue;
    final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, value, compression);
    final StructNodeDelegate structDel =
      new StructNodeDelegate(nodeDel, EFixed.NULL_NODE_KEY.getStandardProperty(), pRightSibKey, pLeftSibKey,
        0, 0);
    return (TextNode)pageTransaction.createNode(new TextNode(nodeDel, valDel, structDel));
  }

  /**
   * Create an {@link AttributeNode}.
   * 
   * @param pParentKey
   *          parent node key
   * @param pName
   *          the {@link QName} of the attribute
   * @return the created node
   * @throws TTIOException
   *           if an I/O error occurs
   */
  AttributeNode createAttributeNode(final long pParentKey, final QName pName, final byte[] pValue)
    throws TTIOException {
    final IPageWriteTrx pageTransaction = getPageTransaction();
    final int nameKey = pageTransaction.createNameKey(PageWriteTrx.buildName(pName), ENode.ATTRIBUTE_KIND);
    final int namespaceKey = pageTransaction.createNameKey(pName.getNamespaceURI(), ENode.NAMESPACE_KIND);
    final NodeDelegate nodeDel =
      new NodeDelegate(pageTransaction.getActualRevisionRootPage().getMaxNodeKey() + 1, pParentKey, 0);
    final NameNodeDelegate nameDel = new NameNodeDelegate(nodeDel, nameKey, namespaceKey);
    final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, pValue, false);

    return (AttributeNode)pageTransaction.createNode(new AttributeNode(nodeDel, nameDel, valDel));
  }

  /**
   * Create an {@link AttributeNode}.
   * 
   * @param pParentKey
   *          parent node key
   * @param pUriKey
   *          the URI key
   * @param pPrefixKey
   *          the prefix key
   * @return the created node
   * @throws TTIOException
   *           if an I/O error occurs
   */
  NamespaceNode createNamespaceNode(final long pParentKey, final int pUriKey, final int pPrefixKey)
    throws TTIOException {
    final IPageWriteTrx pageTransaction = getPageTransaction();
    final NodeDelegate nodeDel =
      new NodeDelegate(pageTransaction.getActualRevisionRootPage().getMaxNodeKey() + 1, pParentKey, 0);
    final NameNodeDelegate nameDel = new NameNodeDelegate(nodeDel, pPrefixKey, pUriKey);

    return (NamespaceNode)pageTransaction.createNode(new NamespaceNode(nodeDel, nameDel));
  }

  // ////////////////////////////////////////////////////////////
  // end of node creations
  // ////////////////////////////////////////////////////////////

  /**
   * Making an intermediate commit based on set attributes.
   * 
   * @throws AbsTTException
   *           if commit fails.
   */
  private void intermediateCommitIfRequired() throws AbsTTException {
    mNodeReadRtx.assertNotClosed();
    if ((mMaxNodeCount > 0) && (mModificationCount > mMaxNodeCount)) {
      commit();
    }
  }

  /**
   * Get the page transaction.
   * 
   * @return the page transaction.
   */
  public IPageWriteTrx getPageTransaction() {
    return (IPageWriteTrx)mNodeReadRtx.getPageTransaction();
  }

  /**
   * Adapting the structure with a hash for all ancestors only with insert.
   * 
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void adaptHashesWithAdd() throws TTIOException {
    if ((mNodeReadRtx.mSession.mResourceConfig.mConsistency != EConsistency.EVENTUAL || !getPageTransaction()
      .getUberPage().isBootstrap())
      && !mBulkInsert) {
      switch (mHashKind) {
      case Rolling:
        rollingAdd();
        break;
      case Postorder:
        postorderAdd();
        break;
      default:
      }
    }
  }

  /**
   * Adapting the structure with a hash for all ancestors only with remove.
   * 
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void adaptHashesWithRemove() throws TTIOException {
    if ((mNodeReadRtx.mSession.mResourceConfig.mConsistency != EConsistency.EVENTUAL || !getPageTransaction()
      .getUberPage().isBootstrap())
      && !mBulkInsert) {
      switch (mHashKind) {
      case Rolling:
        rollingRemove();
        break;
      case Postorder:
        postorderRemove();
        break;
      default:
      }
    }
  }

  /**
   * Adapting the structure with a hash for all ancestors only with update.
   * 
   * @param pOldHash
   *          pOldHash to be removed
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void adaptHashedWithUpdate(final long pOldHash) throws TTIOException {
    if ((mNodeReadRtx.mSession.mResourceConfig.mConsistency != EConsistency.EVENTUAL || !getPageTransaction()
      .getUberPage().isBootstrap())
      && !mBulkInsert) {
      switch (mHashKind) {
      case Rolling:
        rollingUpdate(pOldHash);
        break;
      case Postorder:
        postorderAdd();
        break;
      default:
      }
    }
  }

  /**
   * Removal operation for postorder hash computation.
   * 
   * @throws TTIOException
   *           if anything weird happens
   */
  private void postorderRemove() throws TTIOException {
    moveTo(getNode().getParentKey());
    postorderAdd();
  }

  /**
   * Adapting the structure with a rolling hash for all ancestors only with
   * insert.
   * 
   * @throws TTIOException
   *           if anything weird happened
   */
  private void postorderAdd() throws TTIOException {
    // start with hash to add
    final INode startNode = getNode();
    // long for adapting the hash of the parent
    long hashCodeForParent = 0;
    // adapting the parent if the current node is no structural one.
    if (!(startNode instanceof IStructNode)) {
      final INode node = getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
      node.setHash(mHash.hashLong(mNodeReadRtx.getNode().hashCode()).asLong());
      getPageTransaction().finishNodeModification(node);
      moveTo(mNodeReadRtx.getNode().getParentKey());
    }
    // Cursor to root
    IStructNode cursorToRoot;
    do {
      synchronized (mNodeReadRtx.getNode()) {
        cursorToRoot =
          (IStructNode)getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        hashCodeForParent = mNodeReadRtx.getNode().hashCode() + hashCodeForParent * PRIME;
        // Caring about attributes and namespaces if node is an element.
        if (cursorToRoot.getKind() == ENode.ELEMENT_KIND) {
          final ElementNode currentElement = (ElementNode)cursorToRoot;
          // setting the attributes and namespaces
          final int attCount = ((ElementNode)cursorToRoot).getAttributeCount();
          for (int i = 0; i < attCount; i++) {
            moveTo(currentElement.getAttributeKey(i));
            hashCodeForParent = mNodeReadRtx.getNode().hashCode() + hashCodeForParent * PRIME;
          }
          final int nspCount = ((ElementNode)cursorToRoot).getNamespaceCount();
          for (int i = 0; i < nspCount; i++) {
            moveTo(currentElement.getNamespaceKey(i));
            hashCodeForParent = mNodeReadRtx.getNode().hashCode() + hashCodeForParent * PRIME;
          }
          moveTo(cursorToRoot.getNodeKey());
        }

        // Caring about the children of a node
        if (moveTo(getStructuralNode().getFirstChildKey())) {
          do {
            hashCodeForParent = mNodeReadRtx.getNode().getHash() + hashCodeForParent * PRIME;
          } while (moveTo(getStructuralNode().getRightSiblingKey()));
          moveTo(getStructuralNode().getParentKey());
        }

        // setting hash and resetting hash
        cursorToRoot.setHash(hashCodeForParent);
        getPageTransaction().finishNodeModification(cursorToRoot);
        hashCodeForParent = 0;
      }
    } while (moveTo(cursorToRoot.getParentKey()));

    mNodeReadRtx.setCurrentNode(startNode);
  }

  /**
   * Adapting the structure with a rolling hash for all ancestors only with
   * update.
   * 
   * @param pOldHash
   *          pOldHash to be removed
   * @throws TTIOException
   *           if anything weird happened
   */
  private void rollingUpdate(final long pOldHash) throws TTIOException {
    final INode newNode = getNode();
    final long hash = newNode.hashCode();
    final long newNodeHash = hash;
    long resultNew = hash;

    // go the path to the root
    do {
      synchronized (mNodeReadRtx.getNode()) {
        final INode node =
          getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        if (node.getNodeKey() == newNode.getNodeKey()) {
          resultNew = node.getHash() - pOldHash;
          resultNew = resultNew + newNodeHash;
        } else {
          resultNew = node.getHash() - pOldHash * PRIME;
          resultNew = resultNew + newNodeHash * PRIME;
        }
        node.setHash(resultNew);
        getPageTransaction().finishNodeModification(node);
      }
    } while (moveTo(mNodeReadRtx.getNode().getParentKey()));

    mNodeReadRtx.setCurrentNode(newNode);
  }

  /**
   * Adapting the structure with a rolling hash for all ancestors only with
   * remove.
   * 
   * @throws TTIOException
   *           if anything weird happened
   */
  private void rollingRemove() throws TTIOException {
    final INode startNode = getNode();
    long hashToRemove = startNode.getHash();
    long hashToAdd = 0;
    long newHash = 0;
    // go the path to the root
    do {
      synchronized (mNodeReadRtx.getNode()) {
        final INode node =
          getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        if (node.getNodeKey() == startNode.getNodeKey()) {
          // the begin node is always null
          newHash = 0;
        } else if (node.getNodeKey() == startNode.getParentKey()) {
          // the parent node is just removed
          newHash = node.getHash() - hashToRemove * PRIME;
          hashToRemove = node.getHash();
          setRemoveDescendants(startNode);
        } else {
          // the ancestors are all touched regarding the modification
          newHash = node.getHash() - hashToRemove * PRIME;
          newHash = newHash + hashToAdd * PRIME;
          hashToRemove = node.getHash();
          setRemoveDescendants(startNode);
        }
        node.setHash(newHash);
        hashToAdd = newHash;
        getPageTransaction().finishNodeModification(node);
      }
    } while (moveTo(mNodeReadRtx.getNode().getParentKey()));

    mNodeReadRtx.setCurrentNode(startNode);
  }

  /**
   * Set new descendant count of ancestor after a remove-operation.
   * 
   * @param pStartNode
   *          the node which has been removed
   */
  private void setRemoveDescendants(@Nonnull final INode pStartNode) {
    assert pStartNode != null;
    if (pStartNode instanceof IStructNode) {
      final IStructNode node = ((IStructNode)getNode());
      node.setDescendantCount(node.getDescendantCount() - ((IStructNode)pStartNode).getDescendantCount() - 1);
    }
  }

  /**
   * Adapting the structure with a rolling hash for all ancestors only with
   * insert.
   * 
   * @throws TTIOException
   *           if an I/O error occurs
   */
  private void rollingAdd() throws TTIOException {
    // start with hash to add
    final INode startNode = getNode();
    final long oldDescendantCount = getStructuralNode().getDescendantCount();
    final long descendantCount = oldDescendantCount == 0 ? 1 : oldDescendantCount + 1;
    long hashToAdd =
      startNode.getHash() == 0 ? mHash.hashLong(startNode.hashCode()).asLong() : mHash.hashLong(
        startNode.getHash()).asLong();
    long newHash = 0;
    long possibleOldHash = 0;
    // go the path to the root
    do {
      synchronized (mNodeReadRtx.getNode()) {
        final INode node =
          getPageTransaction().prepareNodeForModification(mNodeReadRtx.getNode().getNodeKey());
        if (node.getNodeKey() == startNode.getNodeKey()) {
          // at the beginning, take the hashcode of the node only
          newHash = hashToAdd;
        } else if (node.getNodeKey() == startNode.getParentKey()) {
          // at the parent level, just add the node
          possibleOldHash = node.getHash();
          newHash = possibleOldHash + hashToAdd * PRIME;
          hashToAdd = newHash;
          setAddDescendants(startNode, node, descendantCount);
        } else {
          // at the rest, remove the existing old key for this element
          // and add the new one
          newHash = node.getHash() - possibleOldHash * PRIME;
          newHash = newHash + hashToAdd * PRIME;
          hashToAdd = newHash;
          possibleOldHash = node.getHash();
          setAddDescendants(startNode, node, descendantCount);
        }
        node.setHash(newHash);
        getPageTransaction().finishNodeModification(node);
      }
    } while (moveTo(mNodeReadRtx.getNode().getParentKey()));
    mNodeReadRtx.setCurrentNode(startNode);
  }

  /**
   * Set new descendant count of ancestor after an add-operation.
   * 
   * @param pStartNode
   *          the node which has been removed
   */
  private void setAddDescendants(@Nonnull final INode pStartNode, @Nonnull final INode pNodeToModifiy,
    @Nonnegative final long pDescendantCount) {
    assert pStartNode != null;
    assert pDescendantCount >= 0;
    assert pNodeToModifiy != null;
    if (pStartNode instanceof IStructNode) {
      final IStructNode node = (IStructNode)pNodeToModifiy;
      final long oldDescendantCount = node.getDescendantCount();
      node.setDescendantCount(oldDescendantCount + pDescendantCount);
    }
  }

  @Override
  public synchronized INodeWriteTrx copySubtreeAsFirstChild(@Nonnull final INodeReadTrx pRtx)
    throws AbsTTException {
    checkNotNull(pRtx);
    checkAccessAndCommit();
    final long nodeKey = getNode().getNodeKey();
    copy(pRtx, EInsertPos.ASFIRSTCHILD);
    moveTo(nodeKey);
    moveToFirstChild();
    return this;
  }

  @Override
  public synchronized INodeWriteTrx copySubtreeAsLeftSibling(@Nonnull final INodeReadTrx pRtx)
    throws AbsTTException {
    checkNotNull(pRtx);
    checkAccessAndCommit();
    final long nodeKey = getNode().getNodeKey();
    copy(pRtx, EInsertPos.ASLEFTSIBLING);
    moveTo(nodeKey);
    moveToFirstChild();
    return this;
  }

  @Override
  public synchronized INodeWriteTrx copySubtreeAsRightSibling(@Nonnull final INodeReadTrx pRtx)
    throws AbsTTException {
    checkNotNull(pRtx);
    checkAccessAndCommit();
    final long nodeKey = getNode().getNodeKey();
    copy(pRtx, EInsertPos.ASRIGHTSIBLING);
    moveTo(nodeKey);
    moveToRightSibling();
    return this;
  }

  /**
   * Helper method for copy-operations.
   * 
   * @param pRtx
   *          the source {@link INodeReadTrx}
   * @param pInsert
   *          the insertion strategy
   * @throws AbsTTException
   *           if anything fails in sirix
   */
  private synchronized void copy(@Nonnull final INodeReadTrx pRtx, @Nonnull final EInsertPos pInsert)
    throws AbsTTException {
    assert pRtx != null;
    assert pInsert != null;
    final INodeReadTrx rtx = pRtx.getSession().beginNodeReadTrx(pRtx.getRevisionNumber());
    assert rtx.getRevisionNumber() == pRtx.getRevisionNumber();
    rtx.moveTo(pRtx.getNode().getNodeKey());
    assert rtx.getNode().getNodeKey() == pRtx.getNode().getNodeKey();
    if (rtx.getNode().getKind() == ENode.ROOT_KIND) {
      rtx.moveToFirstChild();
    }
    if (rtx.getNode().getKind() != ENode.TEXT_KIND && rtx.getNode().getKind() != ENode.ELEMENT_KIND) {
      throw new IllegalStateException("Node to insert must be a structural node (Text or Element)!");
    }
    rtx.getNode().acceptVisitor(new InsertSubtreeVisitor(rtx, this, pInsert));
    rtx.close();
  }

  @Override
  public synchronized INodeWriteTrx replaceNode(@Nonnull final String pXML) throws AbsTTException,
    IOException, XMLStreamException {
    checkNotNull(pXML);
    checkAccessAndCommit();
    final XMLEventReader reader = XMLShredder.createStringReader(checkNotNull(pXML));
    INode insertedRootNode = null;
    if (getNode() instanceof IStructNode) {
      final IStructNode currentNode = getStructuralNode();

      if (pXML.startsWith("<")) {
        while (reader.hasNext()) {
          XMLEvent event = reader.peek();

          if (event.isStartDocument()) {
            reader.nextEvent();
            continue;
          }

          switch (event.getEventType()) {
          case XMLStreamConstants.START_ELEMENT:
            EInsert pos = EInsert.ASFIRSTCHILD;
            if (currentNode.hasLeftSibling()) {
              moveToLeftSibling();
              pos = EInsert.ASRIGHTSIBLING;
            } else {
              moveToParent();
            }

            final XMLShredder shredder = new XMLShredder(this, reader, pos, EShredderCommit.NOCOMMIT);
            shredder.call();
            if (reader.hasNext()) {
              reader.nextEvent(); // End document.
            }

            insertedRootNode = mNodeReadRtx.getNode();
            moveTo(currentNode.getNodeKey());
            remove();
            moveTo(insertedRootNode.getNodeKey());
            break;
          }
        }
      } else {
        insertedRootNode = replaceWithTextNode(pXML);
      }
    }

    moveTo(insertedRootNode.getNodeKey());
    return this;
  }

  @Override
  public synchronized INodeWriteTrx replaceNode(@Nonnull final INodeReadTrx pRtx) throws AbsTTException {
    checkNotNull(pRtx);
    switch (pRtx.getNode().getKind()) {
    case ELEMENT_KIND:
    case TEXT_KIND:
      checkCurrentNode();
      replace(pRtx);
      break;
    case ATTRIBUTE_KIND:
      if (getNode().getKind() != ENode.ATTRIBUTE_KIND) {
        throw new IllegalStateException("Current node must be an attribute node!");
      }
      insertAttribute(pRtx.getQNameOfCurrentNode(), pRtx.getValueOfCurrentNode());
      break;
    case NAMESPACE_KIND:
      if (mNodeReadRtx.getNode().getClass() != NamespaceNode.class) {
        throw new IllegalStateException("Current node must be a namespace node!");
      }
      insertNamespace(pRtx.getQNameOfCurrentNode());
      break;
    }
    return this;
  }

  /**
   * Check current node type (must be a structural node).
   */
  private void checkCurrentNode() {
    if (!(getNode() instanceof IStructNode)) {
      throw new IllegalStateException("Current node must be a structural node!");
    }
  }

  /**
   * Replace current node with a {@link TextNode}.
   * 
   * @param pValue
   *          text value
   * @return inserted node
   * @throws AbsTTException
   *           if anything fails
   */
  private INode replaceWithTextNode(@Nonnull final String pValue) throws AbsTTException {
    assert pValue != null;
    final IStructNode currentNode = getStructuralNode();
    long key = currentNode.getNodeKey();
    if (currentNode.hasLeftSibling()) {
      moveToLeftSibling();
      key = insertTextAsRightSibling(pValue).getNode().getNodeKey();
    } else {
      moveToParent();
      key = insertTextAsFirstChild(pValue).getNode().getNodeKey();
      moveTo(key);
    }

    moveTo(currentNode.getNodeKey());
    remove();
    moveTo(key);
    return mNodeReadRtx.getNode();
  }

  /**
   * Replace a node.
   * 
   * @param pRtx
   *          the transaction which is located at the node to replace
   * @return
   * @throws AbsTTException
   */
  private INode replace(@Nonnull final INodeReadTrx pRtx) throws AbsTTException {
    assert pRtx != null;
    final IStructNode currentNode = getStructuralNode();
    long key = currentNode.getNodeKey();
    if (currentNode.hasLeftSibling()) {
      moveToLeftSibling();
      key = copySubtreeAsRightSibling(pRtx).getNode().getNodeKey();
    } else {
      moveToParent();
      key = copySubtreeAsFirstChild(pRtx).getNode().getNodeKey();
      moveTo(key);
    }

    removeReplaced(currentNode, key);
    return mNodeReadRtx.getNode();
  }

  /**
   * 
   * @param pNode
   * @param pKey
   * @throws AbsTTException
   */
  private void removeReplaced(@Nonnull final IStructNode pNode, @Nonnegative long pKey) throws AbsTTException {
    assert pNode != null;
    assert pKey >= 0;
    moveTo(pNode.getNodeKey());
    remove();
    moveTo(pKey);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this).add("readTrx", mNodeReadRtx.toString()).add("hashKind", mHashKind)
      .toString();
  }

  @Override
  protected INodeReadTrx delegate() {
    return mNodeReadRtx;
  }
}