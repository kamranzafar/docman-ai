db.createUser(
    {
        user: "docman",
        pwd: "docman",
        roles: [
            {
                role: "readWrite",
                db: "docmanDb"
            }
        ]
    }
);
