package org.tron.core.db.fast.callback;

import com.google.protobuf.ByteString;
import com.google.protobuf.Internal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.Hash;
import org.tron.common.utils.ByteUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.utils.RLP;
import org.tron.core.db.Manager;
import org.tron.core.db.fast.storetrie.AccountStateStoreTrie;
import org.tron.core.exception.BadBlockException;
import org.tron.core.trie.TrieImpl;

@Slf4j
@Component
public class FastSyncCallBack {

  private BlockCapsule blockCapsule;
  private volatile boolean execute = false;
  private TrieImpl trie;

  @Setter
  private Manager manager;

  @Autowired
  private AccountStateStoreTrie db;

  private List<TrieEntry> trieEntryList = new ArrayList<>();

  private static class TrieEntry {

    private byte[] key;
    private byte[] data;

    public byte[] getKey() {
      return key;
    }

    public TrieEntry setKey(byte[] key) {
      this.key = key;
      return this;
    }

    public byte[] getData() {
      return data;
    }

    public TrieEntry setData(byte[] data) {
      this.data = data;
      return this;
    }

    public static TrieEntry build(byte[] key, byte[] data) {
      TrieEntry trieEntry = new TrieEntry();
      return trieEntry.setKey(key).setKey(data);
    }
  }

  public void accountCallBack(byte[] key, AccountCapsule item) {
    if (!exe()) {
      return;
    }
    if (item == null || ArrayUtils.isEmpty(item.getData())) {
      return;
    }
    trieEntryList.add(TrieEntry.build(key, item.getData()));
  }

  public void exeTransFinish() {
    for (TrieEntry trieEntry : trieEntryList) {
      trie.put(RLP.encodeElement(trieEntry.getKey()), trieEntry.getData());
    }
    trieEntryList.clear();
  }

  public void deleteAccount(byte[] key) {
    if (!exe()) {
      return;
    }
    trie.delete(RLP.encodeElement(key));
  }

  public void preExecute(BlockCapsule blockCapsule) {
    this.trieEntryList.clear();
    this.blockCapsule = blockCapsule;
    this.execute = true;
    if (!exe()) {
      return;
    }
    byte[] rootHash = null;
    try {
      BlockCapsule parentBlockCapsule = manager.getBlockById(blockCapsule.getParentBlockId());
      rootHash = parentBlockCapsule.getInstance().getBlockHeader().getRawData()
          .getAccountStateRoot().toByteArray();
    } catch (Exception e) {
      logger.error("", e);
    }
    if (Arrays.equals(Internal.EMPTY_BYTE_ARRAY, rootHash)) {
      rootHash = Hash.EMPTY_TRIE_HASH;
    }
    trie = new TrieImpl(db, rootHash);
  }

  public void executePushFinish() throws BadBlockException {
    if (!exe()) {
      return;
    }
    ByteString oldRoot = blockCapsule.getInstance().getBlockHeader().getRawData()
        .getAccountStateRoot();
    execute = false;
    //
    exeTransFinish();
    //
    byte[] newRoot = trie.getRootHash();
    if (ArrayUtils.isEmpty(newRoot)) {
      newRoot = Hash.EMPTY_TRIE_HASH;
    }
    if (oldRoot.isEmpty()) {
//      blockCapsule.setAccountStateRoot(newRoot);
    } else if (!Arrays.equals(oldRoot.toByteArray(), newRoot)) {
      logger.error("The accountStateRoot hash is not validated. {}, oldRoot: {}, newRoot: {}",
          blockCapsule.getBlockId().getString(), ByteUtil.toHexString(oldRoot.toByteArray()),
          ByteUtil.toHexString(newRoot));
      throw new BadBlockException("The accountStateRoot hash is not validated");
    }
  }

  public void executeGenerateFinish() {
    if (!exe()) {
      return;
    }
    //
    exeTransFinish();
    //
    byte[] newRoot = trie.getRootHash();
    if (ArrayUtils.isEmpty(newRoot)) {
      newRoot = Hash.EMPTY_TRIE_HASH;
    }
    blockCapsule.setAccountStateRoot(newRoot);
    execute = false;
  }

  public void exceptionFinish() {
    execute = false;
  }

  private boolean exe() {
    if (!execute || blockCapsule.getNum() < 1) {
      //Agreement same block high to generate account state root
      return false;
    }
    return true;
  }

}
