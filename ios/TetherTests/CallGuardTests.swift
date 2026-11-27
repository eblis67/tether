
    func testHungUpNumberMatchesTheSameCallUnderANewId() {
        var g = HangUpGuard()
        g.hungUp("old-id", number: "+98 912 345 6789", at: t0)
        XCTAssertTrue(g.matchesHungUpNumber("09123456789"))
        XCTAssertTrue(g.matchesHungUpNumber("+989123456789"))
        XCTAssertFalse(g.matchesHungUpNumber("09120000000"))
        g.forget("old-id")
        XCTAssertFalse(g.matchesHungUpNumber("09123456789"))
    }
