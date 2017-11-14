/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.remasc;

import co.rsk.config.RemascConfig;
import co.rsk.peg.Bridge;
import org.apache.commons.collections4.CollectionUtils;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.db.BlockStore;
import org.ethereum.util.BIUtil;
import org.ethereum.util.FastByteComparisons;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.PrecompiledContracts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the actual Remasc distribution logic
 * @author Oscar Guindzberg
 */
public class Remasc {
    private static final Logger logger = LoggerFactory.getLogger(Remasc.class);

    private RemascConfig remascConstants;
    private RemascStorageProvider provider;

    private final Transaction executionTx;
    private Repository repository;

    private Block executionBlock;
    private BlockStore blockStore;

    Remasc(Transaction executionTx, Repository repository, String contractAddress, Block executionBlock, BlockStore blockStore, RemascConfig remascConstants) {
        this.executionTx = executionTx;
        this.repository = repository;
        this.executionBlock = executionBlock;
        this.blockStore = blockStore;
        this.remascConstants = remascConstants;
        this.provider = new RemascStorageProvider(repository, contractAddress);
    }

    public void save() {
        provider.save();
    }

    /**
     * Returns the internal contract state.
     * @return the internal contract state.
     */
    public RemascState getStateForDebugging() {
        return new RemascState(this.provider.getRewardBalance(), this.provider.getBurnedBalance(), this.provider.getSiblings(), this.provider.getBrokenSelectionRule());
    }

    /**
     * Implements the actual Remasc distribution logic
     */
    void processMinersFees() {
        if (!(executionTx instanceof RemascTransaction)) {
            //Detect
            // 1) tx to remasc that is not the latest tx in a block
            // 2) invocation to remasc from another contract (ie call opcode)
            throw new RemascInvalidInvocationException("Invoked Remasc outside last tx of the block");
        }
        this.addNewSiblings();

        long blockNbr = executionBlock.getNumber();

        long processingBlockNumber = blockNbr - remascConstants.getMaturity();
        if (processingBlockNumber < 1 ) {
            logger.debug("First block has not reached maturity yet, current block is {}", blockNbr);
            return;
        }
        BlockHeader processingBlockHeader = blockStore.getBlockByHashAndDepth(executionBlock.getParentHash(), remascConstants.getMaturity() - 1).getHeader();
        // Adds current block fees to accumulated rewardBalance
        BigInteger processingBlockReward = BigInteger.valueOf(processingBlockHeader.getPaidFees());
        BigInteger rewardBalance = provider.getRewardBalance();
        rewardBalance = rewardBalance.add(processingBlockReward);
        provider.setRewardBalance(rewardBalance);

        if (processingBlockNumber - remascConstants.getSyntheticSpan() < 0 ) {
            logger.debug("First block has not reached maturity+syntheticSpan yet, current block is {}", executionBlock.getNumber());
            return;
        }

        // Takes from rewardBalance this block's height reward.
        BigInteger fullBlockReward = rewardBalance.divide(BigInteger.valueOf(remascConstants.getSyntheticSpan()));
        rewardBalance = rewardBalance.subtract(fullBlockReward);
        provider.setRewardBalance(rewardBalance);

        // Pay RSK labs cut
        BigInteger payToRskLabs = fullBlockReward.divide(BigInteger.valueOf(remascConstants.getRskLabsDivisor()));
        transfer(remascConstants.getRskLabsAddress(), payToRskLabs);
        fullBlockReward = fullBlockReward.subtract(payToRskLabs);

        // Pay Federation labs cut
        /* WIP
        Repository processingRepository = repository.getSnapshotTo(processingBlockHeader.getStateRoot());
        Bridge bridge = (Bridge)PrecompiledContracts.getContractForAddress(new DataWord(Hex.decode(PrecompiledContracts.BRIDGE_ADDR)));
        bridge.init(null, null, repository, null, null, null);

        if (bridge.getFederationSize(null).intValue() != 0) {
            BigInteger payToFederation = fullBlockReward.divide(BigInteger.valueOf(remascConstants.getFederationDivisor()));
            // TODO transfer to federators
            fullBlockReward = fullBlockReward.subtract(payToFederation);
        }
        */

        List<Sibling> siblings = provider.getSiblings().get(processingBlockNumber);

        if (CollectionUtils.isNotEmpty(siblings)) {
            // Block has siblings, reward distribution is more complex
            boolean previousBrokenSelectionRule = provider.getBrokenSelectionRule();
            this.payWithSiblings(processingBlockHeader, fullBlockReward, siblings, previousBrokenSelectionRule);
            boolean brokenSelectionRule = this.isBrokenSelectionRule(processingBlockHeader, siblings);
            provider.setBrokenSelectionRule(brokenSelectionRule);
        } else {
            if (provider.getBrokenSelectionRule()) {
                // broken selection rule, apply punishment, ie burn part of the reward.
                BigInteger punishment = fullBlockReward.divide(BigInteger.valueOf(remascConstants.getPunishmentDivisor()));
                fullBlockReward = fullBlockReward.subtract(punishment);
                provider.setBurnedBalance(provider.getBurnedBalance().add(punishment));
            }
            transfer(processingBlockHeader.getCoinbase(), fullBlockReward);
            provider.setBrokenSelectionRule(Boolean.FALSE);
        }

        this.removeUsedSiblings(processingBlockHeader);
    }

    /**
     * Remove siblings just processed if any
     */
    private void removeUsedSiblings(BlockHeader processingBlockHeader) {
        provider.getSiblings().remove(processingBlockHeader.getNumber());
    }

    /**
     * Saves uncles of the current block into the siblings map to use in the future for fee distribution
     */
    private void addNewSiblings() {
        // Add uncles of the execution block to the siblings map
        List<BlockHeader> uncles = executionBlock.getUncleList();
        if (CollectionUtils.isNotEmpty(uncles)) {
            for (BlockHeader uncleHeader : uncles) {
                List<Sibling> siblings = provider.getSiblings().get(uncleHeader.getNumber());
                if (siblings == null)
                    siblings = new ArrayList<>();
                siblings.add(new Sibling(uncleHeader, executionBlock.getHeader().getCoinbase(), executionBlock.getNumber()));
                provider.getSiblings().put(uncleHeader.getNumber(), siblings);
            }
        }
    }

    /**
     * Pay the mainchain block miner, its siblings miners and the publisher miners
     */
    private void payWithSiblings(BlockHeader processingBlockHeader, BigInteger fullBlockReward, List<Sibling> siblings, boolean previousBrokenSelectionRule) {
        SiblingPaymentCalculator paymentCalculator = new SiblingPaymentCalculator(fullBlockReward, previousBrokenSelectionRule, siblings.size(), this.remascConstants);

        this.payPublishersWhoIncludedSiblings(siblings, paymentCalculator.getIndividualPublisherReward());
        provider.addToBurnBalance(paymentCalculator.getPublishersSurplus());

        provider.addToBurnBalance(paymentCalculator.getMinersSurplus());

        this.payIncludedSiblings(siblings, paymentCalculator.getIndividualMinerReward());
        if (previousBrokenSelectionRule) {
            provider.addToBurnBalance(paymentCalculator.getPunishment().multiply(BigInteger.valueOf(siblings.size() + 1L)));
        }

        // Pay to main chain block miner
        transfer(processingBlockHeader.getCoinbase(), paymentCalculator.getIndividualMinerReward());
    }

    private void payPublishersWhoIncludedSiblings(List<Sibling> siblings, BigInteger minerReward) {
        for (Sibling sibling : siblings) {
            transfer(sibling.getIncludedBlockCoinbase(), minerReward);
        }
    }

    private void payIncludedSiblings(List<Sibling> siblings, BigInteger topReward) {
        long perLateBlockPunishmentDivisor = remascConstants.getLateUncleInclusionPunishmentDivisor();
        for (Sibling sibling : siblings) {
            long processingBlockNumber = executionBlock.getNumber() - remascConstants.getMaturity();
            long numberOfBlocksLate = sibling.getIncludedHeight() - processingBlockNumber - 1L;
            BigInteger lateInclusionPunishment = topReward.multiply(BigInteger.valueOf(numberOfBlocksLate)).divide(BigInteger.valueOf(perLateBlockPunishmentDivisor));
            transfer(sibling.getCoinbase(), topReward.subtract(lateInclusionPunishment));
            provider.addToBurnBalance(lateInclusionPunishment);
        }
    }

    private boolean isBrokenSelectionRule(BlockHeader processingBlockHeader, List<Sibling> siblings) {
        // Find out if main chain block selection rule was broken
        for (Sibling sibling : siblings) {
            // Sibling pays significant more fees than block in the main chain OR Sibling has lower hash than block in the main chain
            if (sibling.getPaidFees() > remascConstants.getPaidFeesMultiplier() * processingBlockHeader.getPaidFees() / remascConstants.getPaidFeesDivisor() ||
                    FastByteComparisons.compareTo(sibling.getHash(), 0, 32, processingBlockHeader.getHash(), 0, 32) < 0) {
                return true;
            }
        }

        return false;
    }

    private void transfer(byte[] toAddr, BigInteger value) {
        BIUtil.transfer(repository, Hex.decode(PrecompiledContracts.REMASC_ADDR), toAddr, value);
    }
}

