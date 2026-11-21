package com.kmj.vea.engine

/**
 * The exact runtime-block keys recovered from the eight S5API 16x16 factory
 * initializers and matched against AI_Megpoid.vtb2.
 *
 * Only the 4x2 grid used by the supplied Megpoid V7 bank is included. The
 * original factory tables are still 16x16; unused cells are intentionally
 * omitted rather than populated with guessed values.
 */
object S5MegpoidRuntimeBlockFactoryKeys {
    const val BLOCK_ROWS = 4
    const val BLOCK_COLUMNS = 2

    val ALL: List<S5MegpoidRuntimeBlockFactoryKey> = listOf(
        // FUN_1800f1110 -> residual scale at +0xB8
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 0, 0, "64bd498ceb9692194e89336e38535fbb"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 0, 1, "618d5833c02b39af0ca94f3e179a6e27"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 1, 0, "8afa9aa13ccaebc07385c3bcf1d05a46"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 1, 1, "2b773c5e9c68efefbd4972040f84f0c8"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 2, 0, "13c0375847aa6ad9788a73cf71a4dade"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 2, 1, "42be1806ecd6ed716e3ae096f27e6b4a"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 3, 0, "41d59043f468b3a742e8e31cd79cfd27"),
        key(S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, 3, 1, "21c0b1a1c0c2eab11a722f618343e9db"),

        // FUN_180114660 -> projection optional bias at +0xA8
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 0, 0, "5fbd15e46a32df16513dd0a946c7e441"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 0, 1, "8f07de84219e72944da87cec6ed1e329"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 1, 0, "ae16d33318e89dcea06e6fbf0d160163"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 1, 1, "3b47b1151b78baefc8e68dbfb09eb3a1"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 2, 0, "15270cd102eae9cca7e434d40d1c2472"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 2, 1, "6c727933c8fafba4e41da5f80547c8e6"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 3, 0, "018084cc55995b2f418513aa90421227"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, 3, 1, "51a35fc1c5b62c6d6f7acb326c75d44a"),

        // FUN_18011b770 -> projection weights at +0x98
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 0, 0, "95163c06f04036f8bc060bc46f080a2b"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 0, 1, "6aea11b3bfb6872be87637dbb3d565ec"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 1, 0, "67372791373089d17d460a7bd030ec1e"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 1, 1, "f0d02899d01cfb0efd5f2e6a06a550ad"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 2, 0, "aec7b5500e3262add4f57ee5de9abe7d"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 2, 1, "c8f5f8fcf6264e899af565d76ec8f306"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 3, 0, "cc871bacb5c0b026f2506cafe3c8b361"),
        key(S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, 3, 1, "56fb99ecd4f79c9224e79e9a6cb258f4"),

        // FUN_180106440 -> expansion-layer bias at +0x78
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 0, 0, "135887eb4ccd6fe76c02f50cd4136bfe"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 0, 1, "331bcb363ac37635e09bbf49465fce07"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 1, 0, "7674c8681480dc34260d17042649238c"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 1, 1, "bd81ba739a9e44aee0ddfeb881be711a"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 2, 0, "3d4183d6d0af36f1a05079ae5bacf5c4"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 2, 1, "7fef04e53887aedced3b65f9ba776f9d"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 3, 0, "b890f396f8087a6cb069b6a16e404d4f"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, 3, 1, "66492256d7c08ca02bbbf74b3faae62c"),

        // FUN_18010d550 -> expansion matrix weights at +0x68
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 0, 0, "2be3c647a25538e9536d6757202f82c4"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 0, 1, "de282d6c923b674399f1c1c72c628baf"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 1, 0, "b1465ea6660173b4a49fc88d6cebf333"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 1, 1, "a15cd1ac0be5abbba27a53fe03786820"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 2, 0, "207f5586069747df3328b61d143fd74c"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 2, 1, "633af40060c0bd27f5637d7a495522a6"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 3, 0, "763f264fbcf91eb0ee4f198e3c7668f4"),
        key(S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, 3, 1, "224d0c06603f0018052fa9e634fafde1"),

        // FUN_1800f8220 -> shared descriptor B at +0x48
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 0, 0, "778daaef71320c69cab546db59bafbf3"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 0, 1, "36be567ee41552defe67342665462a18"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 1, 0, "c4f5f1d08c007b8c3163b38fbe6d7411"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 1, 1, "e39a3c39836a1e0faf59da6783841ea0"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 2, 0, "5c25c8dd335e1dbe89db3e62d15a5955"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 2, 1, "8290915c5ce891220d1c05dd9f1721a5"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 3, 0, "550ad26a29b9ca4ea07fe88dd6da8c16"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, 3, 1, "e2bb98d17af85162fef6dcdbec612d11"),

        // FUN_1800ff330 -> shared descriptor A at +0x38
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 0, 0, "36dd5b75d38a5b3dfe3805db6cddb63d"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 0, 1, "4146a9890f80b0e466efaad297040ace"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 1, 0, "0e97b335eca2d346c757e3410fb4180c"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 1, 1, "7517590eeef10c40c4ee0e48e597f3fd"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 2, 0, "15b41b328bb4aa3f2a8935e4a270407f"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 2, 1, "0c5abbc7260dc62397be78aa9d085e1f"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 3, 0, "9b88ad439704ac9bd8863c32b5f7df02"),
        key(S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, 3, 1, "1e8f2ad78e8a5d82d80d0f82bcfae5cf"),

        // FUN_1800ea000 -> depthwise filter tensor (3-D transpose) at +0x20
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 0, 0, "8abf41425bad2554193a661080753070"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 0, 1, "fbaf01274f7d7328e85fe2606636e54a"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 1, 0, "78ab983f61c5c58ff84d45113026ad8a"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 1, 1, "0f551cb9b37ff307437be80b2f80961d"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 2, 0, "771075dad8a161731f1ec7849ffccccc"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 2, 1, "0beaee9f704f4ac85d8f87a10cfeadc1"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 3, 0, "b639196457944060e60e66b234e2a5e1"),
        key(S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, 3, 1, "3294d2ee186d6fedf65089d86b78b302")
    )

    private fun key(
        factory: S5MegpoidRuntimeBlockFactory,
        row: Int,
        column: Int,
        lookupKey: String
    ) = S5MegpoidRuntimeBlockFactoryKey(factory, row, column, lookupKey)
}
